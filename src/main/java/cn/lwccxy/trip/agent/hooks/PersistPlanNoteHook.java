package cn.lwccxy.trip.agent.hooks;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.agent.session.CorpTripSessionKey;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * 仅持久化挂载了 {@link PlanNotebook} 的 Agent 当前计划状态。
 */
public class PersistPlanNoteHook implements Hook {

    /** Reactor Context 中保存 Agent 运行时上下文的键。 */
    private static final String RUNTIME_CONTEXT_KEY = "agentRuntimeContext";

    private final Logger logger = LoggerFactory.getLogger(PersistPlanNoteHook.class);
    private final Session session;

    /**
     * 创建计划状态持久化 Hook。
     *
     * @param session AgentScope 会话存储
     */
    public PersistPlanNoteHook(Session session) {
        this.session = session;
    }

    /**
     * 在计划 Agent 开始执行前恢复当前计划，并在后续生命周期事件中保存当前计划。
     *
     * <p>Hook 只处理挂载了 PlanNotebook 的 ReActAgent，普通 Agent 会直接透传事件。
     * 推理流式分片不会触发保存，避免每个文本分片都写入 Session。</p>
     *
     * @param event AgentScope 生命周期事件
     * @param <T> 事件类型
     * @return 原事件，保持 AgentScope 响应式调用链不变
     */
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return Mono.deferContextual(contextView -> {
            if (event instanceof ReasoningChunkEvent
                    || !(event.getAgent() instanceof ReActAgent agent)) {
                return Mono.just(event);
            }

            PlanNotebook planNotebook = agent.getPlanNotebook();
            if (planNotebook == null) {
                return Mono.just(event);
            }

            AgentRuntimeContext runtimeContext =
                    contextView.getOrDefault(RUNTIME_CONTEXT_KEY, null);
            String sessionId = runtimeContext == null ? null : runtimeContext.getSessionId();
            if (sessionId == null || sessionId.isBlank()) {
                return Mono.just(event);
            }

            // 使用会话 ID 和 Agent 名称隔离同一业务会话中的不同 Agent 计划状态。
            SessionKey sessionKey = new CorpTripSessionKey(sessionId, agent.getName());
            if (event instanceof PreCallEvent) {
                // 新建的 prototype Agent 在正式推理前恢复上一轮尚未结束的计划。
                boolean loaded = planNotebook.loadIfExists(session, sessionKey);
                logger.debug("[PersistPlanNoteHook]历史计划状态加载完成, sessionId={}, agentName={}, loaded={}",
                        sessionId, agent.getName(), loaded);
            } else {
                // 计划工具修改状态后，通过生命周期事件保存最新的 currentPlan 快照。
                planNotebook.saveTo(session, sessionKey);
                logger.debug("[PersistPlanNoteHook]历史计划状态保存完成, sessionId={}, agentName={}",
                        sessionId, agent.getName());
            }
            return Mono.just(event);
        });
    }

    /**
     * 设置 Hook 执行优先级，使计划状态在依赖历史状态的兜底逻辑之前完成加载。
     *
     * @return Hook 优先级
     */
    @Override
    public int priority() {
        return 8;
    }
}
