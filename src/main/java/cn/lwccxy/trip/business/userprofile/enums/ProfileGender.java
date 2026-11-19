package cn.lwccxy.trip.business.userprofile.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 个人档案允许保存的性别。 */
@Getter
@RequiredArgsConstructor
public enum ProfileGender {
    MALE("M", "男"),
    FEMALE("F", "女");

    /** 性别编码，与数据库保存值一致。 */
    @JsonValue
    private final String code;
    /** 前端展示名称。 */
    private final String label;

    /** 严格按业务编码解析，拒绝枚举序号和未定义值。 */
    @JsonCreator
    public static ProfileGender fromCode(String code) {
        // 只允许明确列出的编码，避免枚举名称与数据库编码混用。
        for (ProfileGender value : values()) {
            if (value.code.equals(code)) return value;
        }
        throw new IllegalArgumentException("性别只能填写 M 或 F。");
    }
}
