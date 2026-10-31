package cn.lwccxy.trip.business.trip.domain.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/** 管理员查询待审批出差申请的分页参数。 */
@Getter
@Setter
public class TripApprovalAdminQueryForm {

    /** 当前页码，从 1 开始。 */
    @NotNull
    @Min(1)
    @Max(Integer.MAX_VALUE)
    private Long pageNo = 1L;

    /** 每页记录数，最大 100。 */
    @NotNull
    @Min(1)
    @Max(100)
    private Long pageSize = 20L;
}
