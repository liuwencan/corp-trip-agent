package cn.lwccxy.trip.business.userprofile.enums;

import cn.lwccxy.trip.common.exception.BaseExceptionInterface;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 个人资料接口的业务错误。 */
@Getter
@RequiredArgsConstructor
public enum ProfileErrorCode implements BaseExceptionInterface {
    INVALID_PROFILE("INVALID_PROFILE", "个人资料字段无效，请检查格式、长度及清空字段是否冲突"),
    PROFILE_ALREADY_EXISTS("PROFILE_ALREADY_EXISTS", "个人档案已存在，请使用更新接口"),
    PROFILE_NOT_FOUND("RESOURCE_NOT_FOUND", "个人档案尚未建立，请先创建");

    /** 错误编码，供前端判断失败原因。 */
    private final String errorCode;
    /** 中文错误说明。 */
    private final String errorMessage;
}
