package cn.lwccxy.trip.business.bookingrecord.domain.form;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 新增或部分更新业务预订记录的请求参数。 */
@Getter
@Setter
public class BookingRecordSaveForm {

    /** 关联会话 ID。 */
    @Size(max = 64)
    private String conversationId;
    /** 关联差旅单 ID。 */
    @Size(max = 64)
    private String travelOrderId;
    /** 业务类型。 */
    @Pattern(regexp = "FLIGHT|HOTEL|TRAIN|TICKET|CRUISE|VACATION")
    private String bizType;
    /** 预订平台。 */
    @Size(max = 32)
    private String platform;
    /** 外部平台主订单号。 */
    @Size(max = 128)
    private String externalOrderNo;
    /** 系统统一预订状态。 */
    @Pattern(regexp = "CREATED|PENDING_PAYMENT|PAID|CONFIRMED|COMPLETED|CANCELLED|REFUNDED|FAILED")
    private String status;
    /** 外部平台原始状态。 */
    @Size(max = 64)
    private String externalStatus;
    /** 支付状态。 */
    @Pattern(regexp = "UNPAID|PAID|REFUNDED")
    private String paymentStatus;
    /** 预订标题。 */
    @Size(max = 256)
    private String title;
    /** 订单总金额。 */
    @DecimalMin(value = "0.00")
    @Digits(integer = 10, fraction = 2)
    private BigDecimal totalAmount;
    /** 币种。 */
    @Size(max = 8)
    private String currency;
    /** 联系人姓名。 */
    @Size(max = 64)
    private String contactName;
    /** 联系人手机号。 */
    @Size(max = 32)
    private String contactPhone;
    /** 服务开始时间。 */
    private LocalDateTime startTime;
    /** 服务结束时间。 */
    private LocalDateTime endTime;
    /** 平台原始信息及预订明细 JSON 字符串。 */
    private String detail;
    /** 备注。 */
    @Size(max = 512)
    private String remark;
    /** 下单时间。 */
    private LocalDateTime bookedAt;
}
