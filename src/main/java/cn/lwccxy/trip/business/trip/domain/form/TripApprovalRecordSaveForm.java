package cn.lwccxy.trip.business.trip.domain.form;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 出差申请审批记录新增或修改请求参数。 */
@Getter
@Setter
public class TripApprovalRecordSaveForm extends TripRequestForm {

    /** 审批标题。 */
    @Size(max = 256)
    private String title;

    /** 审批状态。 */
    @Pattern(regexp = "PENDING|APPROVED|REJECTED|CANCELLED")
    private String status;

    /** 审批表单 JSON。 */
    private String approvalForm;

    /** 审批备注。 */
    @Size(max = 512)
    private String remark;

    /** 关联出差申请单 ID。 */
    @Size(max = 64)
    private String orderId;
}
