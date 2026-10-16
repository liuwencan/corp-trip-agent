package cn.lwccxy.trip.business.conversation.enums;

import cn.lwccxy.trip.common.exception.BaseExceptionInterface;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ConversationErrorCode implements BaseExceptionInterface {
    INVALID_PARAMETER("INVALID_PARAMETER", "Invalid request parameter"),
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", "Conversation or message does not exist"),
    CONVERSATION_STATE_INVALID("CONVERSATION_STATE_INVALID", "Conversation state does not allow this operation"),
    CONVERSATION_BUSY("CONVERSATION_BUSY", "Conversation is processing a message"),
    AGENT_FAILED("AGENT_FAILED", "Agent response could not be completed");

    private final String errorCode;
    private final String errorMessage;
}
