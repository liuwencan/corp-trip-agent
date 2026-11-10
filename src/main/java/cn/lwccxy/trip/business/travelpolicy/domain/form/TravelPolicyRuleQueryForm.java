package cn.lwccxy.trip.business.travelpolicy.domain.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/** 差旅政策规则分页查询参数。 */
@Getter
@Setter
public class TravelPolicyRuleQueryForm {

    /** 页码，从 1 开始。 */
    @Min(1)
    private Long pageNo = 1L;

    /** 每页条数，最大 100。 */
    @Min(1)
    @Max(100)
    private Long pageSize = 20L;

    /** 按城市等级筛选。 */
    @Pattern(regexp = "一线|新一线|其他")
    private String cityTier;

    /** 按职级筛选，匹配包含该职级的规则区间。 */
    @Min(0)
    @Max(99)
    private Integer level;
}
