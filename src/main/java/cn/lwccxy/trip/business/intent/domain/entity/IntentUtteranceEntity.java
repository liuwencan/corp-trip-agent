package cn.lwccxy.trip.business.intent.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 意图口语化表达数据库实体。 */
@Data
@TableName("t_intent_utterance")
public class IntentUtteranceEntity {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 目标意图编码，对应 IntentType.targetIntent。 */
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
