package cn.lwccxy.trip.business.trip.domain.form;

import cn.lwccxy.trip.business.conversation.domain.form.HistoryPageForm;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 出差申请单分页查询参数。 */
@Getter
@Setter
public class TripOrderQueryForm extends HistoryPageForm {

    /** 按申请单状态筛选。为空时查询当前用户全部状态。 */
    @Pattern(regexp = "DRAFT|SUBMITTED|APPROVED|REJECTED|COMPLETED|CANCELLED")
    private String status;

    /** 按目的地模糊筛选。 */
    @Size(max = 256)
    private String destination;

    /** 按出发城市模糊筛选。 */
    @Size(max = 128)
    private String departureCity;

    /** 按出发日期精确筛选，格式由现有申请单字符串日期约定决定。 */
    @Size(max = 32)
    private String departureDate;

    /** 按返回日期精确筛选，格式由现有申请单字符串日期约定决定。 */
    @Size(max = 32)
    private String returnDate;
}
