package cn.lwccxy.trip.business.trip.domain.form;

import cn.lwccxy.trip.business.conversation.domain.form.HistoryPageForm;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 出差申请审批记录分页查询参数。 */
@Getter
@Setter
public class TripApprovalRecordQueryForm extends HistoryPageForm {

    /** 按审批状态筛选。 */
    @Pattern(regexp = "PENDING|APPROVED|REJECTED|CANCELLED")
    private String status;

    /** 按关联申请单 ID 筛选。 */
    @Size(max = 64)
    private String orderId;
}
