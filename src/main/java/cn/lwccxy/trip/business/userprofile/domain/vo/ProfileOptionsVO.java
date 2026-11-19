package cn.lwccxy.trip.business.userprofile.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** 前端下拉框及可清空字段的选项集合。 */
@Data
public class ProfileOptionsVO {
    private List<OptionVO> levels;
    private List<OptionVO> genders;
    private List<OptionVO> idTypes;
    private List<OptionVO> editableFields;

    /** 选项编码保留字符串或整数的原始类型，名称统一使用中文。 */
    @Data
    @AllArgsConstructor
    public static class OptionVO {
        private Object code;
        private String label;
    }
}
