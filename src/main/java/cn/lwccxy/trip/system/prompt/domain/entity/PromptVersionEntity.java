package cn.lwccxy.trip.system.prompt.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_prompt_version")
public class PromptVersionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long promptId;

    private Integer versionNo;

    private String systemPrompt;

    private String userTemplate;

    private String outputSchema;

    private String contentHash;

    private String versionStatus;

    private String changeReason;

    private String createdBy;

    private LocalDateTime createdTime;

    private String publishedBy;

    private LocalDateTime publishedTime;
}
