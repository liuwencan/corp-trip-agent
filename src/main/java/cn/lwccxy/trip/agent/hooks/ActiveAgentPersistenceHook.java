package cn.lwccxy.trip.agent.hooks;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.agent.store.activeagent.ActiveAgentSessionStore;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * 活跃Agent持久化Hook
 */
public class ActiveAgentPersistenceHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(ActiveAgentPersistenceHook.class);

    private static final String RUNTIME_CONTEXT_KEY = "agentRuntimeContext";

    private final ActiveAgentSessionStore activeAgentSessionStore;

    public ActiveAgentPersistenceHook(ActiveAgentSessionStore activeAgentSessionStore) {
        this.activeAgentSessionStore = activeAgentSessionStore;
    }

    // 在每次Agent推理前都把当前活跃的Agent记录下来, 方便后续快速路由
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        // 不是推理前的话, 就直接返回这个event
        if (!(event instanceof PreReasoningEvent)) {
            return Mono.just(event);
        }

        return Mono.deferContextual(contextView -> {
            AgentRuntimeContext runtimeContext = contextView.getOrDefault(RUNTIME_CONTEXT_KEY, null);

            if (runtimeContext == null) {
                logger.debug("[ActiveAgentPersistence]  未从 ReactorContext 中提取到会话上下文，跳过活跃 Agent 记录");
                return Mono.just(event);
            }
            String sessionId = runtimeContext.getSessionId();
            String agentName = event.getAgent() != null ? event.getAgent().getName() : null;

            // 如果当前不是MasterAgent就记录一下当前会话活跃的Agent
            if (!Objects.equals(agentName, "MasterAgent")) {
                activeAgentSessionStore.setActiveAgent(sessionId, agentName);
                logger.info("[ActiveAgentPersistence] session={} 活跃 Agent 已切换为 {}", sessionId, agentName);
            }
            return Mono.just(event);
        });
    }
}
