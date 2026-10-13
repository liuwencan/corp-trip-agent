package cn.lwccxy.trip.system.prompt.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PromptVO {

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
