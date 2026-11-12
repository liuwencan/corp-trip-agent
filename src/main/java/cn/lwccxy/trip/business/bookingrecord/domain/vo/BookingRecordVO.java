package cn.lwccxy.trip.business.bookingrecord.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 业务预订记录接口及 Agent 工具返回对象。 */
@Data
public class BookingRecordVO {
    private Long id;
    private String bookingId;
    private String userId;
    private String conversationId;
    private String travelOrderId;
    private String bizType;
    private String platform;
    private String externalOrderNo;
    private String status;
    private String externalStatus;
    private String paymentStatus;
    private String title;
    private BigDecimal totalAmount;
    private String currency;
    private String contactName;
    private String contactPhone;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String detail;
    private String remark;
    private LocalDateTime bookedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
