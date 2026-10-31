package cn.lwccxy.trip.business.trip.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 出差申请单接口返回对象。 */
@Data
public class TripOrderVO {
    private String id;
    private String userId;
    private String destination;
    private String departureCity;
    private String departureDate;
    private String returnDate;
    private String purpose;
    private String status;
    private String approvalId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
