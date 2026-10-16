package cn.lwccxy.trip.business.conversation.domain.form;

import com.fasterxml.jackson.annotation.JsonAnySetter;

public abstract class ConversationRequestForm {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Unsupported request field");
    }
}
