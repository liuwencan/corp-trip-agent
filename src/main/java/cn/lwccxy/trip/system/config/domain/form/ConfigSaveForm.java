package cn.lwccxy.trip.system.config.domain.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ConfigSaveForm {

    @NotBlank
    private String configKey;

    private String configName;

    @NotNull
    private String configValue;

    private String remark;
}
