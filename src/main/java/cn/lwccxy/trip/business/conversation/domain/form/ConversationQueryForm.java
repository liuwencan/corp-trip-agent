package cn.lwccxy.trip.business.conversation.domain.form;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ConversationQueryForm extends HistoryPageForm {
    @NotNull @Pattern(regexp = "ACTIVE|ARCHIVED|CLOSED|ALL")
    private String status = "ACTIVE";
}
