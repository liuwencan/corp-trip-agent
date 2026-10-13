package cn.lwccxy.trip.system.prompt.domain.form;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PromptVersionSaveForm {

    @NotNull
    @Min(1)
    private Integer versionNo;

    @NotBlank
    private String systemPrompt;

    private String userTemplate;

    private String outputSchema;

    @NotBlank
    @Size(max = 1000)
    private String changeReason;
}
