package cn.lwccxy.trip.system.config.domain.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ConfigValueUpdateForm {

    @NotBlank
    private String configKey;

    @NotNull
    private String configValue;
}
