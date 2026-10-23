package cn.lwccxy.trip.agent.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** SSE 流式响应使用的事件类型及其协议值。 */
@Getter
@AllArgsConstructor
public enum SseEventEnum {
    INIT("init", "流式响应初始化事件"),
    STATUS("status", "智能体执行状态事件"),
    THINKING("thinking", "智能体思考过程事件"),
    TOOL("tool", "工具调用事件"),
    TOOL_RESULT("tool_result", "工具执行结果"),
    DELTA("delta", "回答文本增量事件"),
    CONFIRM("confirm", "工具调用确认事件"),
    AGENT_EVENT("agent_event", "未专门处理的智能体事件"),
    COMPLETE("complete", "流式响应完成事件"),
    ERROR("error", "流式响应错误事件"),
    ASK("ask", "用户澄清请求事件");

    private final String code;
    private final String description;
}
