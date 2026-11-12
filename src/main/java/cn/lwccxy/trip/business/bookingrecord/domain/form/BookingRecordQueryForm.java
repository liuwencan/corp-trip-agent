package cn.lwccxy.trip.business.bookingrecord.domain.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 业务预订记录分页查询参数。 */
@Getter
@Setter
public class BookingRecordQueryForm {

    /** 按内部预订单号精确筛选。 */
    @Size(max = 64)
    private String bookingId;
    /** 按会话 ID 精确筛选。 */
    @Size(max = 64)
    private String conversationId;
    /** 按差旅单 ID 精确筛选。 */
    @Size(max = 64)
    private String travelOrderId;
    /** 按业务类型筛选。 */
    @Pattern(regexp = "FLIGHT|HOTEL|TRAIN|TICKET|CRUISE|VACATION")
    private String bizType;
    /** 按预订状态筛选。 */
    @Pattern(regexp = "CREATED|PENDING_PAYMENT|PAID|CONFIRMED|COMPLETED|CANCELLED|REFUNDED|FAILED")
    private String status;
    /** 页码，从 1 开始。 */
    @Min(1)
    private Long pageNo = 1L;
    /** 每页条数，最大 100。 */
    @Min(1)
    @Max(100)
    private Long pageSize = 10L;
}
