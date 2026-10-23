package cn.lwccxy.trip.business.conversation.enums;

import cn.lwccxy.trip.common.exception.BaseExceptionInterface;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ConversationErrorCode implements BaseExceptionInterface {
    INVALID_PARAMETER("INVALID_PARAMETER", "请求参数无效"),
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", "会话或消息不存在"),
    CONVERSATION_STATE_INVALID("CONVERSATION_STATE_INVALID", "当前会话状态不允许执行此操作"),
    CONVERSATION_BUSY("CONVERSATION_BUSY", "会话正在处理消息"),
    AGENT_FAILED("AGENT_FAILED", "智能助手响应处理失败");

    private final String errorCode;
    private final String errorMessage;
}
