package cn.lwccxy.trip.common.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import cn.lwccxy.trip.business.conversation.domain.form.ConversationRequestForm;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChatRequestDTO extends ConversationRequestForm {

    @NotBlank(message = "用户问题不能为空")
    @Size(max = 20000)
    private String question;

    @Positive
    private Long conversationId;
}
