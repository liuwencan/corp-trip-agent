package cn.lwccxy.trip.system.prompt.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PromptReleaseLogVO {

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
