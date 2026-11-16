package cn.lwccxy.trip.agent.hooks;

import cn.lwccxy.trip.agent.AgentRegistry;
import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 登记显式配置此 Hook 的主 Agent 和子 Agent。 */
@Component
public class AgentRegistryHook implements Hook {
    private static final String RUNTIME_CONTEXT_KEY = "agentRuntimeContext";

    private final AgentRegistry agentRegistry;

    /**
     * 创建 Agent 登记 Hook。
     *
     * @param agentRegistry 会话 Agent 注册表
     */
    public AgentRegistryHook(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
    }

    /**
     * 在 Agent 调用生命周期开始和结束时更新会话下的活跃 Agent 集合。
     *
     * @param event AgentScope Hook 事件
     * @param <T> Hook 事件类型
     * @return 原始事件，保持 AgentScope 调用流程不变
     */
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return Mono.deferContextual(contextView -> {
            AgentRuntimeContext runtimeContext = contextView.getOrDefault(RUNTIME_CONTEXT_KEY, null);
            if (runtimeContext == null || runtimeContext.getSessionId() == null
                    || runtimeContext.getSessionId().isBlank() || event.getAgent() == null) {
                return Mono.just(event);
            }

            String sessionId = runtimeContext.getSessionId();
            if (event instanceof PreCallEvent) {
                // PreCall 发生在 Agent 正式处理消息前，子 Agent 也会经过此处登记。
                agentRegistry.register(sessionId, event.getAgent());
            } else if (event instanceof PostCallEvent || event instanceof ErrorEvent) {
                // 正常完成和异常结束都清理当前实例，避免中断请求命中已结束的 Agent。
                agentRegistry.unregister(sessionId, event.getAgent());
            }
            return Mono.just(event);
        });
    }
}
