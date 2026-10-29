package cn.lwccxy.trip.business.intent.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 意图口语化表达接口返回对象。 */
@Data
public class IntentUtteranceVO {

    /** 主键。 */
    private Long id;

    /** 目标意图编码。 */
    private String intentCode;

    /** 用户口语化表达或拆分后的子句。 */
    private String utteranceText;

    /** 数据状态。 */
    private String status;

    /** 管理备注。 */
    private String remark;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;
}
