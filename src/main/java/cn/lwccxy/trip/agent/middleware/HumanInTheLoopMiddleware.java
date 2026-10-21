package cn.lwccxy.trip.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import reactor.core.publisher.Flux;

import java.util.function.Consumer;
import java.util.function.Function;

public class HumanInTheLoopMiddleware implements MiddlewareBase {

    /** 运行上下文中用于传递用户澄清工具输入的回调键。 */
    public static final String ASK_USER_CALLBACK_KEY = "corpTrip.askUserCallback";

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
        // 在工具执行前发送完整参数，随后继续执行，由 AskUserTool 负责挂起智能体。
        Consumer<ToolUseBlock> callback = ctx.get(ASK_USER_CALLBACK_KEY);
        if (callback != null) {
            for (ToolUseBlock tool : input.toolCalls()) {
                if ("ask_user".equals(tool.getName())) {
                    callback.accept(tool);
                }
            }
        }
        return MiddlewareBase.super.onActing(agent, ctx, input, next);
    }
}
