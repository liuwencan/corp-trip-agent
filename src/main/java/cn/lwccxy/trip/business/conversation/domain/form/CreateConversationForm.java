package cn.lwccxy.trip.business.conversation.domain.form;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateConversationForm extends ConversationRequestForm {
    @Size(min = 1, max = 200)
    private String title;

    public void setTitle(String title) { this.title = title == null ? null : title.strip(); }
}
