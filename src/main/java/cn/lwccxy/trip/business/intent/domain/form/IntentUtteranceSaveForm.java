package cn.lwccxy.trip.business.intent.domain.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 新增或修改意图口语化表达的请求参数。 */
@Getter
@Setter
public class IntentUtteranceSaveForm {

    /** 目标意图编码，对应 IntentType.targetIntent。 */
    @NotBlank
    @Size(max = 64)
    private String intentCode;

    /** 用户口语化表达或拆分后的子句。 */
    @NotBlank
    @Size(max = 500)
    private String utteranceText;

    /** 数据状态。 */
    @Pattern(regexp = "ACTIVE|INACTIVE")
    private String status = "ACTIVE";

    /** 管理备注。 */
    @Size(max = 500)
    private String remark;
}
