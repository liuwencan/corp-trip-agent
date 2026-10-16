package cn.lwccxy.trip.business.conversation.domain.form;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;

@Getter
public class MessageFeedbackForm extends ConversationRequestForm {
    @Pattern(regexp = "LIKE|DISLIKE")
    private String feedback;
    @JsonIgnore
    private boolean feedbackPresent;

    @JsonSetter("feedback")
    public void setFeedback(String feedback) {
        this.feedback = feedback;
        this.feedbackPresent = true;
    }

    @AssertTrue
    @JsonIgnore
    public boolean isFeedbackPresent() { return feedbackPresent; }
}
