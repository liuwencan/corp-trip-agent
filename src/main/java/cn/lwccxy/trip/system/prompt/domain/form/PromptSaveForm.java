package cn.lwccxy.trip.system.prompt.domain.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PromptSaveForm {

    @NotBlank
    @Size(max = 128)
    private String promptKey;

    @NotBlank
    @Size(max = 255)
    private String promptName;

    @NotBlank
    @Size(max = 128)
    private String agentCapability;

    @Size(max = 1000)
    private String description;
}
