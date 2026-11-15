package cn.lwccxy.trip.business.agenttrace.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.lwccxy.trip.business.agenttrace.domain.form.AgentTraceQueryForm;
import cn.lwccxy.trip.business.agenttrace.domain.form.AgentTraceSaveForm;
import cn.lwccxy.trip.business.agenttrace.domain.vo.AgentTraceDetailVO;
import cn.lwccxy.trip.business.agenttrace.domain.vo.AgentTraceVO;
import cn.lwccxy.trip.business.agenttrace.service.AgentTraceService;
import cn.lwccxy.trip.common.response.PageResponse;
import cn.lwccxy.trip.common.response.Response;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 管理员查询和维护 Agent 调用链路 Span 的接口。 */
@Validated
@RestController
@RequiredArgsConstructor
@SaCheckRole("ADMIN")
@RequestMapping("/business/agent-traces")
public class AdminAgentTraceController {

    private final AgentTraceService service;

    /** 分页查询 Agent 调用链路 Span。 */
    @GetMapping
    public PageResponse<AgentTraceVO> query(@Valid @ModelAttribute AgentTraceQueryForm form) {
        return service.query(form);
    }

    /** 按数据库主键查询单条 Span。 */
    @GetMapping("/spans/{id}")
    public Response<AgentTraceVO> get(@Positive @PathVariable Long id) {
        return service.get(id);
    }

    /** 查询同一 traceId 下的全部 Span。 */
    @GetMapping("/trace/{traceId}")
    public Response<AgentTraceDetailVO> getByTraceId(@PathVariable @Size(max = 64) String traceId) {
        return service.getByTraceId(traceId);
    }

    /** 新增 Agent 调用链路 Span。 */
    @PostMapping
    public Response<AgentTraceVO> create(@Valid @RequestBody AgentTraceSaveForm form) {
        return service.create(form);
    }

    /** 更新 Agent 调用链路 Span。 */
    @PutMapping("/spans/{id}")
    public Response<AgentTraceVO> update(@Positive @PathVariable Long id,
                                         @Valid @RequestBody AgentTraceSaveForm form) {
        return service.update(id, form);
    }

    /** 删除 Agent 调用链路 Span。 */
    @DeleteMapping("/spans/{id}")
    public Response<Void> delete(@Positive @PathVariable Long id) {
        return service.delete(id);
    }
}
