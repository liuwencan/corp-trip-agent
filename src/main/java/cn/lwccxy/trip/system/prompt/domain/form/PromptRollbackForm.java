package cn.lwccxy.trip.system.prompt.domain.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PromptRollbackForm {

    @NotNull
    private Long versionId;

    @NotBlank
    @Size(max = 1000)
    private String reason;
}
