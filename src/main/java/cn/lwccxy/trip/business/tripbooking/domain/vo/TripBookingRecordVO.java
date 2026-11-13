package cn.lwccxy.trip.business.tripbooking.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 供应商预订记录工具返回对象。 */
@Data
public class TripBookingRecordVO {
    private String id;
    private String tripOrderId;
    private String bizType;
    private String productKey;
    private String providerCode;
    private String providerOrderId;
    private String productSummary;
    private String status;
    private String providerStatus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
