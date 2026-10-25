package cn.lwccxy.trip.agent.middleware;

import cn.lwccxy.trip.agent.enums.SseEventEnum;
import cn.lwccxy.trip.agent.sse.SseEmitterRegistry;
import cn.lwccxy.trip.agent.sse.SseEmitterSender;
import cn.lwccxy.trip.common.util.JsonUtil;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

public class HumanInTheLoopMiddleware implements MiddlewareBase {


    public static final String ASK_USER_TOOL = "ask_user";

    private final SseEmitterRegistry sseEmitterRegistry;

    /**
     * 创建工具执行结果中间件。
     *
     * @param sseEmitterRegistry SSE 会话连接注册表
     */
    public HumanInTheLoopMiddleware(SseEmitterRegistry sseEmitterRegistry) {
        this.sseEmitterRegistry = sseEmitterRegistry;
    }


    /**
     * 在工具执行前捕获用户澄清工具参数，并调用当前会话注册的推送回调。
     *
     * @param agent 当前执行的智能体
     * @param ctx 当前智能体运行上下文
     * @param input 本轮待执行的工具调用
     * @param next 后续中间件或工具执行逻辑
     * @return 工具执行事件流
     */
    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        String agentName = agent.getName();
        String sessionId = ctx.getSessionId();
        SseEmitter emitter = sseEmitterRegistry.getEmitter(sessionId);


        List<ToolUseBlock> toolUseBlocks = input.toolCalls();
        Optional<ToolUseBlock> optional = toolUseBlocks.stream()
                .filter(toolUseBlock -> toolUseBlock.getName().equals(ASK_USER_TOOL))
                .findFirst();

        optional.ifPresent(block -> {
            Map<String, Object> askUserToolInput = block.getInput();
            // 把对应的askUser工具入参推送给前端, 前端做表单渲染
            SseEmitterSender.send(emitter, SseEventEnum.ASK, JsonUtil.toJson(askUserToolInput));
        });

        // 放行
        return MiddlewareBase.super.onActing(agent, ctx, input, next);
    }
}
