package cn.lwccxy.trip.agent.store.activeagent;

import io.agentscope.core.session.Session;
import io.agentscope.core.state.SimpleSessionKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * @author Hollis
 */
@Component
public class ActiveAgentSessionStore {

    private static final Logger logger = LoggerFactory.getLogger(ActiveAgentSessionStore.class);

    /**
     * Session 中存储 activeAgent 的 field 名称
     */
    private static final String ACTIVE_AGENT_FIELD = "activeAgent";

    /**
     * Session 中用于隔离路由状态的 key 后缀。
     * 完整 key 为 {@code sessionId + ":router"}，避免与各 Agent 的 memory 状态冲突。
     */
    private static final String ROUTER_SESSION_KEY_SUFFIX = ":router";

    @Autowired
    private Session session;

    /**
     * 从持久化 Session 中查询当前会话的活跃 Agent。
     *
     * <p>每次对话开始时由 ChatPipeline 调用，
     * 确保集群环境下任意节点都能获取到最新的 activeAgent。</p>
     */
    public String getActiveAgent(String sessionId) {
        try {
            return session.get(buildSessionKey(sessionId), ACTIVE_AGENT_FIELD, ActiveAgentState.class)
                    .map(ActiveAgentState::agentName)
                    .orElse(null);
        } catch (Exception e) {
            logger.error("[ActiveAgentSessionStore] 从 Session 读取 activeAgent 失败, sessionId={}", sessionId, e);
            return null;
        }
    }

    /**
     * 将当前会话的活跃 Agent 持久化到 Session 中。
     */
    public void setActiveAgent(String sessionId, String agentName) {
        try {
            session.save(buildSessionKey(sessionId), ACTIVE_AGENT_FIELD, new ActiveAgentState(agentName));
            logger.debug("[ActiveAgentSessionStore] 已持久化 activeAgent, sessionId={}, agentName={}", sessionId, agentName);
        } catch (Exception e) {
            logger.error("[ActiveAgentSessionStore] 持久化 activeAgent 失败, sessionId={}, agentName={}", sessionId, agentName, e);
        }
    }

    /**
     * 清除当前会话的活跃 Agent 路由状态。
     */
    public void clear(String sessionId) {
        try {
            session.delete(buildSessionKey(sessionId));
            logger.debug("[ActiveAgentSessionStore] 已清除 activeAgent, sessionId={}", sessionId);
        } catch (Exception e) {
            logger.error("[ActiveAgentSessionStore] 清除 activeAgent 失败, sessionId={}", sessionId, e);
        }
    }

    private SimpleSessionKey buildSessionKey(String sessionId) {
        return SimpleSessionKey.of(sessionId + ROUTER_SESSION_KEY_SUFFIX);
    }
}
