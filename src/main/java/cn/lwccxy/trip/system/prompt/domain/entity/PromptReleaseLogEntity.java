package cn.lwccxy.trip.system.prompt.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_prompt_release_log")
public class PromptReleaseLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long promptId;

    private String operationType;

    private Long targetVersionId;

    private Long previousVersionId;

    private String operationReason;

    private String operatorId;

    private String operationResult;

    private String failureReason;

    private LocalDateTime createTime;
}
