package cn.lwccxy.trip.agent;

import cn.lwccxy.trip.agent.store.CorpTripSessionKey;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 按 AgentScope 会话标识维护当前活跃的 Agent 实例。 */
@Component
public class AgentRegistry {

    /** Agent 中断广播使用的 Redis Pub/Sub 频道。 */
    public static final String INTERRUPT_CHANNEL = "corp-trip-agent:agent:interrupt";

    @Resource
    private Session session;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final Logger logger = LoggerFactory.getLogger(AgentRegistry.class);

    // 同一会话可以同时运行主 Agent 和多个子 Agent。
    private final Map<String, Set<Agent>> localAgents = new ConcurrentHashMap<>();

    /**
     * 将 Agent 加入会话的活跃实例集合。
     *
     * @param sessionId AgentScope 会话标识
     * @param agent 当前会话的 Agent 实例
     */
    public void register(String sessionId, Agent agent) {
        if (sessionId == null || sessionId.isBlank() || agent == null) {
            throw new IllegalArgumentException("sessionId and agent are required");
        }

        // 按会话原子创建或复用集合，避免并发注册时丢失其他 Agent。
        localAgents.compute(sessionId, (key, agents) -> {
            Set<Agent> sessionAgents = agents == null
                    ? ConcurrentHashMap.newKeySet() : agents;
            // PreCall 可能在同一 Agent 的多轮处理中重复触发，仅在首次加入时记录注册审计日志。
            boolean registered = sessionAgents.add(agent);
            if (registered) {
                logger.info("[AgentRegistry] Agent 注册成功，sessionId={}, agentName={}, activeAgentCount={}",
                        sessionId, agent.getName(), sessionAgents.size());
            } else {
                logger.debug("[AgentRegistry] Agent 已在活跃集合中，跳过重复注册，sessionId={}, agentName={}",
                        sessionId, agent.getName());
            }
            return sessionAgents;
        });
    }

    /**
     * 中断指定会话当前登记的 Agent。
     *
     * @param sessionId AgentScope 会话标识
     * @return 找到活跃 Agent 并发出中断请求时返回 {@code true}
     */
    public boolean interrupt(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }

        // 先尝试本机快速路径；仅本机未命中时才通过 Redis 通知其他节点。
        if (interruptLocally(sessionId)) {
            return true;
        }

        logger.info("[AgentRegistry] 当前机器节点未发现可中断的活跃 Agent，向集群广播中断请求，sessionId={}",
                sessionId);
        try {
            // Pub/Sub 将请求投递给所有节点，由各节点自行判断是否持有该会话的活跃 Agent。
            Long subscriberCount = redisTemplate.convertAndSend(INTERRUPT_CHANNEL, sessionId);
            logger.info("[AgentRegistry] 已广播会话中断请求，sessionId={}, subscriberCount={}",
                    sessionId, subscriberCount);
        } catch (RuntimeException exception) {
            // Redis 不可用时保留现有未命中语义，避免广播故障影响请求线程。
            logger.warn("[AgentRegistry] 广播会话中断请求失败，sessionId={}", sessionId, exception);
        }
        // 跨节点处理是异步的；只有命中节点会负责后续 SSE 通知。
        return false;
    }

    /**
     * 仅中断当前节点登记的 Agent，不触发 Redis 广播。
     *
     * @param sessionId AgentScope 会话标识
     * @return 当前节点找到活跃 Agent 并发出中断请求时返回 {@code true}
     */
    public boolean interruptLocally(String sessionId) {
        Set<Agent> sessionAgents = sessionId == null ? null : localAgents.get(sessionId);
        if (sessionAgents == null || sessionAgents.isEmpty()) {
            return false;
        }

        // 对集合快照逐个发出中断请求，确保同一会话下的主 Agent 和子 Agent 都能收到中断。
        Set<Agent> agentsToInterrupt = Set.copyOf(sessionAgents);
        if (agentsToInterrupt.isEmpty()) {
            return false;
        }
        logger.info("[AgentRegistry] 开始中断会话下的活跃 Agent，sessionId={}, agentCount={}",
                sessionId, agentsToInterrupt.size());
        for (Agent agent : agentsToInterrupt) {
            agent.interrupt();
            logger.info("[AgentRegistry] 已向 Agent 发出中断请求，sessionId={}, agentName={}",
                    sessionId, agent.getName());

            // 手动保存每个 Agent 的会话，防止中断发生在 Hook 事件之间而丢失消息。
            ReActAgent rctAgent = (ReActAgent)agent;
            SessionKey sessionKey = new CorpTripSessionKey(sessionId, rctAgent.getName());
            rctAgent.saveTo(session, sessionKey);
            PlanNotebook planNotebook = rctAgent.getPlanNotebook();
            if (planNotebook != null) {
                // 计划 Agent 由专用 Hook 管理计划状态；中断路径没有 Hook 事件时在这里补充保存。
                planNotebook.saveTo(session, sessionKey);
            }
        }
        return true;
    }

    /**
     * 从会话的活跃实例集合中移除指定 Agent；集合为空时一并移除会话键。
     *
     * @param sessionId AgentScope 会话标识
     * @param agent 要移除的 Agent 实例
     */
    public void unregister(String sessionId, Agent agent) {
        if (sessionId != null && !sessionId.isBlank() && agent != null) {
            // 与注册使用同一个会话级原子操作，避免空集合清理覆盖并发加入的 Agent。
            localAgents.computeIfPresent(sessionId, (key, sessionAgents) -> {
                // 仅当实例实际存在并成功移除时记录状态变更，避免误报重复清理。
                boolean removed = sessionAgents.remove(agent);
                if (removed) {
                    logger.info("[AgentRegistry] Agent 移除成功，sessionId={}, agentName={}, activeAgentCount={}",
                            sessionId, agent.getName(), sessionAgents.size());
                } else {
                    logger.debug("[AgentRegistry] Agent 不在活跃集合中，跳过移除，sessionId={}, agentName={}",
                            sessionId, agent.getName());
                }
                return sessionAgents.isEmpty() ? null : sessionAgents;
            });
        }
    }

}
