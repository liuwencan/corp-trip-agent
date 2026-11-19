package cn.lwccxy.trip.business.userprofile.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 与预订业务及现有数据库一致的证件类型。 */
@Getter
@RequiredArgsConstructor
public enum ProfileIdType {
    ID_CARD(0, "身份证"),
    PASSPORT(1, "护照"),
    OTHER(2, "其他"),
    HOME_RETURN_PERMIT(3, "回乡证"),
    MILITARY_ID(4, "军官证"),
    POLICE_ID(5, "警官证"),
    HONG_KONG_MACAU_PERMIT(6, "港澳通行证"),
    TAIWAN_COMPATRIOT_PERMIT(7, "台胞证"),
    TAIWAN_TRAVEL_PERMIT(8, "台湾通行证"),
    FOREIGN_PERMANENT_RESIDENT_ID(9, "外国人永久居留身份证");

    /** 证件类型编码，与数据库整数值一致。 */
    @JsonValue
    private final int code;
    /** 前端展示名称。 */
    private final String label;

    /** 将数据库或工具输入的编码转换成已定义的证件类型。 */
    public static ProfileIdType fromCode(int code) {
        for (ProfileIdType value : values()) {
            if (value.code == code) return value;
        }
        throw new IllegalArgumentException("证件类型只能填写 0 到 9。");
    }

    /** 请求只接受整数编码，拒绝字符串、浮点数以及枚举序号转换。 */
    @JsonCreator
    public static ProfileIdType fromJson(JsonNode value) {
        // 显式检查 JSON 类型，避免 1.5 被自动截断为合法编码 1。
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("证件类型必须使用整数编码。");
        }
        return fromCode(value.intValue());
    }
}
