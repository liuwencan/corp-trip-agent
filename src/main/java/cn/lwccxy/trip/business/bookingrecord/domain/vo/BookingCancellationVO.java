package cn.lwccxy.trip.business.bookingrecord.domain.vo;

import lombok.Data;

/** 区分供应商取消结果和本地同步结果，避免把部分成功当作全部成功。 */
@Data
public class BookingCancellationVO {

    /** 内部预订单号。 */
    private String bookingId;
    /** 已核实的本地预订状态；无法确认时为空。 */
    private String status;
    /** 供应商确认取消为 true，明确拒绝为 false，未调用或结果不明确时为空。 */
    private Boolean providerCancelled;
    /** 本地记录是否已确认处于取消状态。 */
    private boolean localUpdated;
}
