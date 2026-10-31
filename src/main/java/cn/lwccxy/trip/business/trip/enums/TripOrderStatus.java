package cn.lwccxy.trip.business.trip.enums;

/** 出差申请单状态。 */
public enum TripOrderStatus {
    /** 草稿。 */
    DRAFT,
    /** 已提交。 */
    SUBMITTED,
    /** 审批通过。 */
    APPROVED,
    /** 审批驳回。 */
    REJECTED,
    /** 已完成。 */
    COMPLETED,
    /** 已取消。 */
    CANCELLED
}
