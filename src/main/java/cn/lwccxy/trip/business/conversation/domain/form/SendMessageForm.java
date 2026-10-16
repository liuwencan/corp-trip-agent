package cn.lwccxy.trip.business.conversation.domain.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SendMessageForm extends ConversationRequestForm {
    @NotBlank @Size(max = 20000)
    private String content;
}
