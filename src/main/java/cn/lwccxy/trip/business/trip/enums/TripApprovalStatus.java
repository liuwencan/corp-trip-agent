package cn.lwccxy.trip.business.trip.enums;

/** 出差申请审批记录状态。 */
public enum TripApprovalStatus {
    /** 待审批。 */
    PENDING,
    /** 审批通过。 */
    APPROVED,
    /** 审批驳回。 */
    REJECTED,
    /** 审批取消。 */
    CANCELLED
}
