package cn.lwccxy.trip.system.prompt.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PromptVersionVO {

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
    private boolean current;
}
