package cn.lwccxy.trip.agent.hooks;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.agent.enums.SseEventEnum;
import cn.lwccxy.trip.agent.sse.SseEmitterRegistry;
import cn.lwccxy.trip.agent.sse.SseEmitterSender;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ConfirmationHook implements Hook {
    private final Logger logger = LoggerFactory.getLogger(ConfirmationHook.class);
    private final SseEmitterRegistry sseEmitterRegistry;
    private final AgentRuntimeContext agentRuntimeContext;

    /**
     * 创建确认 Hook。
     *
     * @param sseEmitterRegistry SSE 连接注册表
     * @param agentRuntimeContext 当前 Agent 对应的运行时上下文
     */
    public ConfirmationHook(SseEmitterRegistry sseEmitterRegistry, AgentRuntimeContext agentRuntimeContext) {
        this.sseEmitterRegistry = sseEmitterRegistry;
        this.agentRuntimeContext = agentRuntimeContext;
    }


    private final String ASK_USER = "ask_user";

    /**
     * 处理推理完成事件，提取 ask_user 工具参数并通知前端等待用户输入。
     *
     * @param event AgentScope Hook 事件
     * @param <T> Hook 事件类型
     * @return 原始 Hook 事件
     */
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return Mono.deferContextual(contextView -> {
            // 优先从 Reactor Context 获取，未传入时使用 Agent 创建时保存的上下文
            AgentRuntimeContext runtimeContext =
                    contextView.getOrDefault("agentRuntimeContext", agentRuntimeContext);

            if (event instanceof PostReasoningEvent e) {
                logger.info("ConfirmationHook开始执行");

                String sessionId = runtimeContext.getSessionId();
                SseEmitter emitter = sseEmitterRegistry.getEmitter(sessionId);

                Msg reasoningMsg = e.getReasoningMessage();
                List<ToolUseBlock> toolCalls =
                        reasoningMsg.getContentBlocks(ToolUseBlock.class);

                Optional<ToolUseBlock> askUserTool = toolCalls.stream()
                        .filter(toolCall -> ASK_USER.equals(toolCall.getName()))
                        .findFirst();

                if (askUserTool.isPresent()) {
                    if (emitter != null) {
                        Map<String, Object> input = askUserTool.get().getInput();
                        String toolCallId = askUserTool.get().getId();
                        // 将工具调用标识和用户澄清参数一起发送，便于前端渲染并在恢复调用时关联原工具请求。
                        Map<String, Object> askPayload = Map.of(
                                "toolCallId", toolCallId,
                                "input", input
                        );
                        SseEmitterSender.send(
                                emitter,
                                SseEventEnum.ASK,
                                askPayload
                        );
                    }
                    e.stopAgent();
                }
            }

            return Mono.just(event);
        });
    }

}
