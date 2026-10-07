package cn.lwccxy.trip.system.config.domain.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class ConfigQueryForm {

    @Min(1)
    private long pageNo = 1;

    @Min(1)
    @Max(200)
    private long pageSize = 10;

    private String keyword;
}
