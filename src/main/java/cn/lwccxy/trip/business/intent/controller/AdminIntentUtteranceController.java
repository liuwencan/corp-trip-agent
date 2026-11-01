package cn.lwccxy.trip.business.intent.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.lwccxy.trip.business.intent.domain.form.IntentUtteranceQueryForm;
import cn.lwccxy.trip.business.intent.domain.form.IntentUtteranceSaveForm;
import cn.lwccxy.trip.business.intent.domain.vo.IntentUtteranceVO;
import cn.lwccxy.trip.business.intent.service.IntentUtteranceService;
import cn.lwccxy.trip.common.response.PageResponse;
import cn.lwccxy.trip.common.response.Response;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 管理员维护意图口语化表达的后台接口。 */
@Validated
@RestController
@RequiredArgsConstructor
@SaCheckRole("ADMIN")
@RequestMapping("/intent/utterances")
public class AdminIntentUtteranceController {

    private final IntentUtteranceService service;

    /**
     * 分页查询意图表达。
     *
     * @param form 分页和筛选条件
     * @return 分页数据
     */
    @GetMapping
    public PageResponse<IntentUtteranceVO> query(@Valid @ModelAttribute IntentUtteranceQueryForm form) {
        return service.query(form);
    }

    /**
     * 查询单条意图表达。
     *
     * @param id 意图表达主键
     * @return 查询结果
     */
    @GetMapping("/{id}")
    public Response<IntentUtteranceVO> get(@Positive @PathVariable Long id) {
        return service.get(id);
    }

    /**
     * 新增意图口语化表达。
     *
     * @param form 新增参数
     * @return 新增结果
     */
    @PostMapping
    public Response<IntentUtteranceVO> create(@Valid @RequestBody IntentUtteranceSaveForm form) {
        return service.create(form);
    }

    /**
     * 更新意图口语化表达。
     *
     * @param id 意图表达主键
     * @param form 更新参数
     * @return 更新结果
     */
    @PutMapping("/{id}")
    public Response<IntentUtteranceVO> update(@Positive @PathVariable Long id,
                                              @Valid @RequestBody IntentUtteranceSaveForm form) {
        return service.update(id, form);
    }

    /**
     * 删除意图口语化表达。
     *
     * @param id 意图表达主键
     * @return 删除结果
     */
    @DeleteMapping("/{id}")
    public Response<Void> delete(@Positive @PathVariable Long id) {
        return service.delete(id);
    }
}
