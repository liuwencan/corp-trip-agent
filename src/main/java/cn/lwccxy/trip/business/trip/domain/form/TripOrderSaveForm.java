package cn.lwccxy.trip.business.trip.domain.form;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 出差申请单新增或修改请求参数。 */
@Getter
@Setter
public class TripOrderSaveForm extends TripRequestForm {

    /** 目的地。 */
    @Size(max = 256)
    private String destination;

    /** 出发城市。 */
    @Size(max = 128)
    private String departureCity;

    /** 出发日期，按数据库约定保存为字符串。 */
    @Size(max = 32)
    private String departureDate;

    /** 返回日期，按数据库约定保存为字符串。 */
    @Size(max = 32)
    private String returnDate;

    /** 出差事由。 */
    @Size(max = 512)
    private String purpose;

    /** 申请单状态。 */
    @Pattern(regexp = "DRAFT|SUBMITTED|APPROVED|REJECTED|COMPLETED|CANCELLED")
    private String status;

    /** 关联审批单 ID。 */
    @Size(max = 64)
    private String approvalId;
}
