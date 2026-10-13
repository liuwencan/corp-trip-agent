package cn.lwccxy.trip.system.prompt.domain.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PromptOperationForm {

    @NotBlank
    @Size(max = 1000)
    private String reason;
}
