package cn.lwccxy.trip.business.eval.intent.domain.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 意图识别评测请求参数。 */
@Getter
@Setter
public class IntentEvaluationForm {

    /** 待评测的用户原始问题。 */
    @NotBlank(message = "用户问题不能为空")
    @Size(max = 20000, message = "用户问题长度不能超过20000")
    private String question;
}
