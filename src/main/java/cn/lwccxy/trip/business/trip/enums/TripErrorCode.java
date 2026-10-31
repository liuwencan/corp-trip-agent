package cn.lwccxy.trip.business.trip.enums;

import cn.lwccxy.trip.common.exception.BaseExceptionInterface;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 出差申请和审批业务错误码。 */
@Getter
@RequiredArgsConstructor
public enum TripErrorCode implements BaseExceptionInterface {
    /** 请求参数不符合业务要求。 */
    INVALID_PARAMETER("INVALID_PARAMETER", "请求参数无效"),
    /** 申请单或审批记录不存在，或不属于当前用户。 */
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", "出差申请单或审批记录不存在"),
    /** 申请单关联关系不合法。 */
    ORDER_RELATION_INVALID("ORDER_RELATION_INVALID", "审批记录关联的申请单无效");

    private final String errorCode;
    private final String errorMessage;
}
