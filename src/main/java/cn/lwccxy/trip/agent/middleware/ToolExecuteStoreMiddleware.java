package cn.lwccxy.trip.agent.middleware;

import cn.lwccxy.trip.agent.sse.SseEmitterSender;
import cn.lwccxy.trip.agent.enums.SseEventEnum;
import cn.lwccxy.trip.business.conversation.domain.entity.MessageEntity;
import cn.lwccxy.trip.business.conversation.mapper.MessageMapper;
import cn.lwccxy.trip.agent.sse.SseEmitterRegistry;
import cn.lwccxy.trip.common.util.JsonUtil;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 工具调用结果持久化Middleware
 */
public class ToolExecuteStoreMiddleware implements MiddlewareBase {

    private final SseEmitterRegistry sseEmitterRegistry;

    public ToolExecuteStoreMiddleware(SseEmitterRegistry sseEmitterRegistry) {
        this.sseEmitterRegistry = sseEmitterRegistry;
    }


    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        String sessionId = (String)ctx.get("sessionId");
        String agentName = agent.getName();

        SseEmitter emitter = sseEmitterRegistry.getEmitter(sessionId);

        // 1. 获取到调用的工具
        List<ToolUseBlock> toolUseBlocks = input.toolCalls();
        for (ToolUseBlock toolUseBlock : toolUseBlocks) {
            String toolCallId = toolUseBlock.getId();
            String toolName = toolUseBlock.getName();
            Map<String, Object> toolInput = toolUseBlock.getInput();
            String toolInputJson = JsonUtil.toJson(toolInput);
            SseEmitterSender.send(emitter, SseEventEnum.TOOL,
                    Map.of("agentName", agentName, "toolCallId", toolCallId, "toolName", toolName, "input", toolInputJson));
        }

        return next.apply(input)
                .flatMap(event -> {
                    // 工具执行结果推送, 前端用toolCallId关联起来
                    if (event instanceof ToolResultTextDeltaEvent) {
                        String delta = ((ToolResultTextDeltaEvent) event).getDelta();
                        SseEmitterSender.send(emitter, SseEventEnum.TOOL_RESULT,
                                Map.of("toolCallId", ((ToolResultEndEvent) event).getToolCallId(), "toolResult", delta));
                    }
                    return Flux.just(event);
                });
    }
}
