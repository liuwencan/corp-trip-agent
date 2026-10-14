package cn.lwccxy.trip.system.prompt.domain.vo;

import lombok.Data;

@Data
public class PromptVersionCompareVO {

    private Long promptId;
    private Long leftVersionId;
    private Integer leftVersionNo;
    private Long rightVersionId;
    private Integer rightVersionNo;
    private boolean contentChanged;
    private String leftPromptContent;
    private String rightPromptContent;
}
