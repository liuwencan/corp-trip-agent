package cn.lwccxy.trip.business.conversation.domain.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class HistoryPageForm {
    @NotNull @Min(1) @Max(Integer.MAX_VALUE)
    private Long pageNo = 1L;
    @NotNull @Min(1) @Max(100)
    private Long pageSize = 20L;
}
