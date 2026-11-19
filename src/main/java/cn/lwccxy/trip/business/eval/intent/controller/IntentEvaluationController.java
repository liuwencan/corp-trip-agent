package cn.lwccxy.trip.business.eval.intent.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.lwccxy.trip.business.eval.intent.domain.form.IntentEvaluationForm;
import cn.lwccxy.trip.business.eval.intent.domain.vo.IntentEvaluationVO;
import cn.lwccxy.trip.business.eval.intent.service.IntentEvaluationService;
import cn.lwccxy.trip.common.response.Response;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 为离线脚本提供意图识别评测接口。 */
@RestController
@RequiredArgsConstructor
@SaCheckRole("ADMIN")
@RequestMapping("/business/eval/intent")
public class IntentEvaluationController {

    private final IntentEvaluationService service;

    /**
     * 使用线上相同的识别流程评测单条原始问题。
     *
     * @param form 评测请求参数
     * @return 统一响应包装的评测结果
     */
    @PostMapping("/recognize")
    public Response<IntentEvaluationVO> recognize(@Valid @RequestBody IntentEvaluationForm form) {
        return Response.success(service.evaluate(form));
    }
}
