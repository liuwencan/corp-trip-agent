package cn.lwccxy.trip.business.userprofile.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 用户可编辑和显式清空的字段白名单。 */
@Getter
@RequiredArgsConstructor
public enum ProfileField {
    NICK_NAME("nickName", "昵称"),
    AVATAR("avatar", "头像"),
    BASE_CITY("baseCity", "常驻城市"),
    LEVEL("level", "职级"),
    NAME_PINYIN("namePinyin", "姓名拼音"),
    EMAIL("email", "邮箱"),
    CHINESE_NAME("chineseName", "中文姓名"),
    ID_TYPE("idType", "证件类型"),
    ID_NUMBER("idNumber", "证件号码"),
    PHONE("phone", "手机号"),
    GENDER("gender", "性别");

    /** 字段编码，只映射允许用户自行修改的属性。 */
    @JsonValue
    private final String code;
    /** 前端展示名称。 */
    private final String label;

    /** 校验清空请求只能引用已开放的资料字段。 */
    @JsonCreator
    public static ProfileField fromCode(String code) {
        for (ProfileField value : values()) {
            if (value.code.equals(code)) return value;
        }
        throw new IllegalArgumentException("不可清空未开放的个人资料字段。");
    }
}
