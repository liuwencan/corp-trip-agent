package cn.lwccxy.trip.business.trip.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 出差申请审批记录接口返回对象。 */
@Data
public class TripApprovalRecordVO {
    private String id;
    private String userId;
    private String title;
    private String status;
    private String approvalForm;
    private String remark;
    private LocalDateTime submitTime;
    private LocalDateTime updateTime;
    private String orderId;
}
