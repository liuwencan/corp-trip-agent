package cn.lwccxy.trip.business.bookingrecord.enums;

import cn.lwccxy.trip.common.exception.BaseExceptionInterface;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 业务预订记录错误码。 */
@Getter
@RequiredArgsConstructor
public enum BookingRecordErrorCode implements BaseExceptionInterface {
    /** 请求字段或状态值不符合预订记录约束。 */
    INVALID_PARAMETER("INVALID_PARAMETER", "预订记录参数无效"),
    /** 已完成或已退款的记录不能再改为已取消。 */
    INVALID_STATUS("INVALID_STATUS", "当前预订状态不允许取消"),
    /** 记录不存在或不属于当前用户。 */
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", "预订记录不存在");

    private final String errorCode;
    private final String errorMessage;
}
