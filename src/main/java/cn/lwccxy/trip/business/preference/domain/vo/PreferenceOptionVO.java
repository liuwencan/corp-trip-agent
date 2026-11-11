package cn.lwccxy.trip.business.preference.domain.vo;

import lombok.Data;

import java.util.List;

/** 单个偏好字段和其允许值。 */
@Data
public class PreferenceOptionVO {

    /** 保存时使用的字段编码。 */
    private String key;

    /** 用户可见的字段名称。 */
    private String label;

    /** 字段选择方式，single 表示单选，multi 表示多选。 */
    private String type;

    /** 允许保存的选项值。 */
    private List<String> options;
}
