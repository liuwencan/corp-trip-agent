package cn.lwccxy.trip.business.intent.domain.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/** 意图口语化表达分页查询参数。 */
@Getter
@Setter
public class IntentUtteranceQueryForm {

    /** 页码，从 1 开始。 */
    @Min(1)
    private Long pageNo = 1L;

    /** 每页条数，最大 100。 */
    @Min(1)
    @Max(100)
    private Long pageSize = 20L;

    /** 按目标意图编码筛选。 */
    private String intentCode;

    /** 按启用状态筛选，ALL 表示全部状态。 */
    @Pattern(regexp = "ACTIVE|INACTIVE|ALL")
    private String status = "ALL";

    /** 按口语化表达模糊搜索。 */
    private String keyword;
}
