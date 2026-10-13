package cn.lwccxy.trip.system.prompt.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_prompt")
public class PromptEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String promptKey;

    private String promptName;

    private String agentCapability;

    private String description;

    private Long currentVersionId;

    private String status;

    private String createdBy;

    private String updatedBy;

    private LocalDateTime updateTime;

    private LocalDateTime createTime;
}
