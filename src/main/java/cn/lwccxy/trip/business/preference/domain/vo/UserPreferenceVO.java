package cn.lwccxy.trip.business.preference.domain.vo;

import lombok.Data;

import java.util.Map;

/** 当前用户从长期记忆召回的偏好展示数据。 */
@Data
public class UserPreferenceVO {

    /** 当前登录用户标识。 */
    private String userId;

    /** 长期记忆返回的原始偏好摘要。 */
    private String memorySummary;

    /** 映射到预定义选项的结构化偏好。 */
    private Map<String, Object> preferences;
}
