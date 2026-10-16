package cn.lwccxy.trip.business.conversation.domain.form;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateConversationForm extends ConversationRequestForm {
    @Size(min = 1, max = 200)
    private String title;
    @Pattern(regexp = "ACTIVE|ARCHIVED|CLOSED")
    private String status;

    public void setTitle(String title) { this.title = title == null ? null : title.strip(); }

    @AssertTrue
    @JsonIgnore
    public boolean isChangePresent() { return title != null || status != null; }
}
