package cn.lwccxy.trip.system.prompt.domain.vo;

import lombok.Data;

@Data
public class PromptVersionCompareVO {

    private Long promptId;
    private Long leftVersionId;
    private Integer leftVersionNo;
    private Long rightVersionId;
    private Integer rightVersionNo;
    private boolean systemPromptChanged;
    private boolean userTemplateChanged;
    private boolean outputSchemaChanged;
    private String leftSystemPrompt;
    private String rightSystemPrompt;
    private String leftUserTemplate;
    private String rightUserTemplate;
    private String leftOutputSchema;
    private String rightOutputSchema;
}
