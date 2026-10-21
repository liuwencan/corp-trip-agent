package cn.lwccxy.trip.business.chat.domain.form;

import cn.lwccxy.trip.business.conversation.domain.form.ConversationRequestForm;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 提交用户补充信息并恢复指定会话的请求参数。 */
@Getter
@Setter
public class ChatResumeForm extends ConversationRequestForm {

    @NotNull
    @Positive
    private Long conversationId;

    @NotBlank
    @Size(max = 128)
    private String toolCallId;

    @NotBlank
    @Size(max = 20000)
    private String answer;
}
