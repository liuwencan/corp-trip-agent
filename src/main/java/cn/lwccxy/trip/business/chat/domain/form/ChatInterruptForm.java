package cn.lwccxy.trip.business.chat.domain.form;

import cn.lwccxy.trip.business.conversation.domain.form.ConversationRequestForm;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

/** 指定需要中断的会话。 */
@Getter
@Setter
public class ChatInterruptForm extends ConversationRequestForm {
    @NotNull
    @Positive
    private Long conversationId;
}
