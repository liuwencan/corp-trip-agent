package cn.lwccxy.trip.business.bookingrecord.domain.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 预订业务类型枚举
 * <ul>
 *   <li>FLIGHT   - 机票</li>
 *   <li>HOTEL    - 酒店</li>
 *   <li>TRAIN    - 火车票</li>
 * </ul>
 *
 * @author Hollis
 */
public enum BookingType {

    /**
     * 机票
     */
    FLIGHT("FLIGHT", "机票"),
    /**
     * 酒店
     */
    HOTEL("HOTEL", "酒店"),
    /**
     * 火车票
     */
    TRAIN("TRAIN", "火车票");

    /**
     * 存入数据库的值
     */
    @EnumValue
    private final String code;

    /**
     * 中文显示名
     */
    private final String label;

    BookingType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 根据 code 安全解析，非法值返回 null
     */
    public static BookingType of(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (BookingType t : values()) {
            if (t.code.equalsIgnoreCase(code)) {
                return t;
            }
        }
        return null;
    }
}
