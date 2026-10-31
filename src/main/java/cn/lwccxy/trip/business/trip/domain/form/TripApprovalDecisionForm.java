package cn.lwccxy.trip.business.trip.domain.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 管理员审批出差申请的决定参数。 */
@Getter
@Setter
public class TripApprovalDecisionForm {

    /** 审批结论，只允许通过或驳回。 */
    @NotBlank
    @Pattern(regexp = "APPROVED|REJECTED")
    private String decision;

    /** 管理员审批意见，驳回时必填。 */
    @Size(max = 512)
    private String remark;
}
