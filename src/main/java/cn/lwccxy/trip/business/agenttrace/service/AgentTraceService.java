package cn.lwccxy.trip.business.agenttrace.service;

import cn.lwccxy.trip.business.agenttrace.domain.entity.AgentTraceEntity;
import cn.lwccxy.trip.business.agenttrace.domain.form.AgentTraceQueryForm;
import cn.lwccxy.trip.business.agenttrace.domain.form.AgentTraceSaveForm;
import cn.lwccxy.trip.business.agenttrace.domain.vo.AgentTraceDetailVO;
import cn.lwccxy.trip.business.agenttrace.domain.vo.AgentTraceVO;
import cn.lwccxy.trip.business.agenttrace.mapper.AgentTraceMapper;
import cn.lwccxy.trip.common.response.PageResponse;
import cn.lwccxy.trip.common.response.Response;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.time.Duration;
import java.time.LocalDateTime;

/** 提供 Agent 调用链路 Span 的查询与 CRUD 服务。 */
@Service
@RequiredArgsConstructor
public class AgentTraceService {

    private final AgentTraceMapper mapper;

    /** 按链路、会话、用户、类型、状态和开始时间筛选 Span。 */
    public PageResponse<AgentTraceVO> query(AgentTraceQueryForm form) {
        if (form.getStartFrom() != null && form.getStartTo() != null
                && form.getStartFrom().isAfter(form.getStartTo())) {
            return failedPage("INVALID_AGENT_TRACE_TIME_RANGE", "开始时间范围不合法");
        }

        // 使用数据库筛选与分页，避免将包含输入快照和工具结果的全量追踪数据加载到内存。
        LambdaQueryWrapper<AgentTraceEntity> query = new LambdaQueryWrapper<>();
        if (form.getTraceId() != null && !form.getTraceId().isBlank()) {
            query.eq(AgentTraceEntity::getTraceId, form.getTraceId().strip());
        }
        if (form.getSessionId() != null) {
            query.eq(AgentTraceEntity::getSessionId, form.getSessionId());
        }
        if (form.getUserId() != null) {
            query.eq(AgentTraceEntity::getUserId, form.getUserId());
        }
        if (form.getSpanType() != null) {
            query.eq(AgentTraceEntity::getSpanType, form.getSpanType());
        }
        if (form.getStatus() != null) {
            query.eq(AgentTraceEntity::getStatus, form.getStatus());
        }
        if (form.getStartFrom() != null) {
            query.ge(AgentTraceEntity::getStartTime, form.getStartFrom());
        }
        if (form.getStartTo() != null) {
            query.le(AgentTraceEntity::getStartTime, form.getStartTo());
        }
        query.orderByDesc(AgentTraceEntity::getStartTime).orderByDesc(AgentTraceEntity::getId);

        Page<AgentTraceEntity> page = mapper.selectPage(new Page<>(form.getPageNo(), form.getPageSize()), query);
        return PageResponse.success(page.getRecords().stream().map(this::toVO).toList(),
                page.getCurrent(), page.getTotal(), page.getSize());
    }

    /** 根据数据库主键查询单条 Span。 */
    public Response<AgentTraceVO> get(Long id) {
        AgentTraceEntity entity = mapper.selectById(id);
        return entity == null
                ? Response.fail("AGENT_TRACE_NOT_FOUND", "链路记录不存在")
                : Response.success(toVO(entity));
    }

    /** 查询指定 traceId 下的全部 Span，并按开始时间排序供前端还原链路树。 */
    public Response<AgentTraceDetailVO> getByTraceId(String traceId) {
        List<AgentTraceEntity> entities = mapper.selectList(new LambdaQueryWrapper<AgentTraceEntity>()
                .eq(AgentTraceEntity::getTraceId, traceId)
                .orderByAsc(AgentTraceEntity::getStartTime)
                .orderByAsc(AgentTraceEntity::getId));
        return entities.isEmpty()
                ? Response.fail("AGENT_TRACE_NOT_FOUND", "链路记录不存在")
                : Response.success(toTraceDetail(traceId, entities));
    }

    /** 汇总单次对话的墙钟耗时和全部模型调用 Token 用量。 */
    private AgentTraceDetailVO toTraceDetail(String traceId, List<AgentTraceEntity> entities) {
        AgentTraceDetailVO detail = new AgentTraceDetailVO();
        detail.setTraceId(traceId);
        detail.setTotalDurationMs(totalDurationMs(entities));
        // 模型推理与上下文总结都会产生模型 Token 用量，每个 Span 只计入一次。
        detail.setTotalInputTokens(entities.stream().filter(this::isModelSpan)
                .map(AgentTraceEntity::getInputTokens)
                .filter(java.util.Objects::nonNull).mapToLong(Long::longValue).sum());
        detail.setTotalOutputTokens(entities.stream().filter(this::isModelSpan)
                .map(AgentTraceEntity::getOutputTokens)
                .filter(java.util.Objects::nonNull).mapToLong(Long::longValue).sum());
        detail.setSpans(entities.stream().map(this::toVO).toList());
        return detail;
    }

    /** 判断 Span 是否属于模型调用，模型推理和上下文总结均纳入对话 Token 汇总。 */
    private boolean isModelSpan(AgentTraceEntity entity) {
        return "MODEL".equals(entity.getSpanType()) || "SUMMARY".equals(entity.getSpanType());
    }

    /** 优先使用根 Agent Span 耗时；缺失时按整条链路的最早开始和最晚结束时间计算。 */
    private Long totalDurationMs(List<AgentTraceEntity> entities) {
        Long rootDuration = entities.stream()
                .filter(entity -> "AGENT".equals(entity.getSpanType()) && entity.getParentSpanId() == null)
                .map(AgentTraceEntity::getDurationMs)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (rootDuration != null) {
            return rootDuration;
        }

        LocalDateTime earliestStart = entities.stream().map(AgentTraceEntity::getStartTime)
                .filter(java.util.Objects::nonNull).min(LocalDateTime::compareTo).orElse(null);
        if (earliestStart == null) {
            return 0L;
        }
        LocalDateTime latestEnd = entities.stream().map(entity -> entity.getEndTime() == null
                        ? LocalDateTime.now() : entity.getEndTime())
                .max(LocalDateTime::compareTo).orElse(earliestStart);
        return Math.max(0L, Duration.between(earliestStart, latestEnd).toMillis());
    }

    /** 新增 Span 记录，并校验 JSON 字段及链路内 Span 唯一性。 */
    @Transactional
    public Response<AgentTraceVO> create(AgentTraceSaveForm form) {
        String invalidJsonField = invalidJsonField(form);
        if (invalidJsonField != null) {
            return Response.fail("INVALID_AGENT_TRACE_JSON", invalidJsonField + "必须是合法 JSON");
        }
        AgentTraceEntity entity = toEntity(form);
        try {
            mapper.insert(entity);
        } catch (DuplicateKeyException exception) {
            // trace_id 与 span_id 的唯一索引负责解决并发新增时的最终冲突。
            return Response.fail("AGENT_TRACE_SPAN_ALREADY_EXISTS", "该链路下已存在相同 Span ID");
        }
        return Response.success(toVO(entity));
    }

    /** 更新 Span 记录，不允许通过请求体修改数据库主键和创建时间。 */
    @Transactional
    public Response<AgentTraceVO> update(Long id, AgentTraceSaveForm form) {
        AgentTraceEntity old = mapper.selectById(id);
        if (old == null) {
            return Response.fail("AGENT_TRACE_NOT_FOUND", "链路记录不存在");
        }
        String invalidJsonField = invalidJsonField(form);
        if (invalidJsonField != null) {
            return Response.fail("INVALID_AGENT_TRACE_JSON", invalidJsonField + "必须是合法 JSON");
        }

        AgentTraceEntity entity = toEntity(form);
        entity.setId(id);
        entity.setCreateTime(old.getCreateTime());
        try {
            mapper.updateById(entity);
        } catch (DuplicateKeyException exception) {
            return Response.fail("AGENT_TRACE_SPAN_ALREADY_EXISTS", "该链路下已存在相同 Span ID");
        }
        return Response.success(toVO(mapper.selectById(id)));
    }

    /** 根据主键删除 Span 记录。 */
    @Transactional
    public Response<Void> delete(Long id) {
        return mapper.deleteById(id) == 0
                ? Response.fail("AGENT_TRACE_NOT_FOUND", "链路记录不存在")
                : Response.success();
    }

    /** 校验表单中的 JSON 快照均为合法 JSON 文本。 */
    private String invalidJsonField(AgentTraceSaveForm form) {
        if (!isValidJson(form.getInputMessages())) {
            return "inputMessages";
        }
        if (!isValidJson(form.getOutputMessage())) {
            return "outputMessage";
        }
        if (!isValidJson(form.getToolResult())) {
            return "toolResult";
        }
        if (!isValidJson(form.getToolInput())) {
            return "toolInput";
        }
        if (!isValidJson(form.getAttributes())) {
            return "attributes";
        }
        return null;
    }

    /** 判断可空 JSON 字段是否为空或包含合法 JSON。 */
    private boolean isValidJson(String value) {
        return value == null || value.isBlank() || JSON.isValid(value);
    }

    /** 将表单字段复制到数据库实体，并清理可选文本的空白值。 */
    private AgentTraceEntity toEntity(AgentTraceSaveForm form) {
        AgentTraceEntity entity = new AgentTraceEntity();
        BeanUtils.copyProperties(form, entity);
        entity.setTraceId(form.getTraceId().strip());
        entity.setSpanId(form.getSpanId().strip());
        entity.setParentSpanId(normalize(form.getParentSpanId()));
        entity.setAgentId(normalize(form.getAgentId()));
        entity.setAgentName(normalize(form.getAgentName()));
        entity.setOperationName(normalize(form.getOperationName()));
        entity.setModelName(normalize(form.getModelName()));
        entity.setToolName(normalize(form.getToolName()));
        entity.setToolCallId(normalize(form.getToolCallId()));
        entity.setToolInput(normalize(form.getToolInput()));
        entity.setErrorSummary(normalize(form.getErrorSummary()));
        entity.setInputMessages(normalize(form.getInputMessages()));
        entity.setOutputMessage(normalize(form.getOutputMessage()));
        entity.setToolResult(normalize(form.getToolResult()));
        entity.setAttributes(normalize(form.getAttributes()));
        return entity;
    }

    /** 将空白可选文本统一转换为空值。 */
    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** 将数据库实体转换为接口视图，避免向接口层暴露持久化实体。 */
    private AgentTraceVO toVO(AgentTraceEntity entity) {
        AgentTraceVO vo = new AgentTraceVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    /** 构造符合分页响应结构的失败结果。 */
    private PageResponse<AgentTraceVO> failedPage(String errorCode, String message) {
        PageResponse<AgentTraceVO> response = new PageResponse<>();
        response.setSuccess(false);
        response.setErrorCode(errorCode);
        response.setMessage(message);
        return response;
    }
}
