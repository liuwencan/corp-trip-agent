package cn.lwccxy.trip.business.userprofile.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 当前用户档案支持的职级，扩展时应同步确认差旅政策。 */
@Getter
@RequiredArgsConstructor
public enum ProfileLevel {
    P5("P5", "五级"),
    P6("P6", "六级"),
    P7("P7", "七级"),
    P8("P8", "八级");

    /** 职级编码，与数据库保存值一致。 */
    @JsonValue
    private final String code;
    /** 前端展示名称。 */
    private final String label;

    /** 校验职级必须属于当前开放的枚举集合。 */
    @JsonCreator
    public static ProfileLevel fromCode(String code) {
        for (ProfileLevel value : values()) {
            if (value.code.equals(code)) return value;
        }
        throw new IllegalArgumentException("职级只能填写 P5、P6、P7 或 P8。");
    }
}
