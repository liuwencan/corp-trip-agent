package cn.lwccxy.trip.business.bookingrecord.domain.entity;

import cn.lwccxy.trip.business.bookingrecord.domain.enums.BookingType;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 业务预订记录数据库实体。 */
@Data
@TableName(value = "booking_record", autoResultMap = true)
public class BookingRecordEntity {

    /** 数据库自增主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 系统生成的全局预订单号。 */
    private String bookingId;
    /** 所属用户 ID。 */
    private String userId;
    /** 关联会话 ID。 */
    private String conversationId;
    /** 关联差旅单 ID。 */
    private String travelOrderId;
    /** 预订业务类型。 */
    private BookingType bizType;
    /** 预订平台标识。 */
    private String platform;
    /** 外部平台主订单号。 */
    private String externalOrderNo;
    /** 系统统一预订状态。 */
    private String status;
    /** 外部平台原始状态。 */
    private String externalStatus;
    /** 支付状态。 */
    private String paymentStatus;
    /** 预订标题。 */
    private String title;
    /** 订单总金额。 */
    private BigDecimal totalAmount;
    /** 币种。 */
    private String currency;
    /** 联系人姓名。 */
    private String contactName;
    /** 联系人手机号。 */
    private String contactPhone;
    /** 服务开始时间。 */
    private LocalDateTime startTime;
    /** 服务结束时间。 */
    private LocalDateTime endTime;
    /** 平台原始信息及预订明细 JSON。 */
    private String detail;
    /** 备注。 */
    private String remark;
    /** 下单时间。 */
    private LocalDateTime bookedAt;
    /** 创建时间。 */
    private LocalDateTime createdAt;
    /** 更新时间。 */
    private LocalDateTime updatedAt;
    /** 逻辑删除标志。 */
    private Integer deleted;
}
