package cn.lwccxy.trip.business.preference.domain.vo;

import lombok.Data;

import java.util.List;

/** 偏好选项分类及其可选项。 */
@Data
public class PreferenceCategoryVO {

    /** 分类编码。 */
    private String category;

    /** 用户可见的分类名称。 */
    private String label;

    /** 分类中的偏好字段。 */
    private List<PreferenceOptionVO> items;
}
