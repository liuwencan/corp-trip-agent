package cn.lwccxy.trip.agent.hooks;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.business.agenttrace.domain.entity.AgentTraceEntity;
import cn.lwccxy.trip.business.agenttrace.service.AgentTracePersistenceService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.ErrorEvent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PostSummaryEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.hook.PreSummaryEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** 持久化 Agent、模型推理、工具和上下文总结 Span 的 Hook。 */
public class AgentTracePersistenceHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(AgentTracePersistenceHook.class);
    private static final String RUNTIME_CONTEXT_KEY = "agentRuntimeContext";
    private static final int HOOK_PRIORITY = 55;

    private final AgentTracePersistenceService persistenceService;
    private final Map<String, AgentExecution> executions = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentLinkedQueue<PendingAgentTool>> pendingAgentTools = new ConcurrentHashMap<>();

    /** 创建专用于 Span 持久化的 Hook。 */
    public AgentTracePersistenceHook(AgentTracePersistenceService persistenceService) {
        this.persistenceService = persistenceService;
    }

    /** 在监控日志之后执行持久化，避免数据库耗时混入监控 Hook 的节点计时。 */
    @Override
    public int priority() {
        return HOOK_PRIORITY;
    }

    /** 分发 AgentScope 生命周期事件，并吞掉持久化异常以保证观测逻辑不影响 Agent 主流程。 */
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return Mono.deferContextual(contextView -> {
            try {
                AgentRuntimeContext runtimeContext = contextView.getOrDefault(RUNTIME_CONTEXT_KEY, null);
                dispatch(event, runtimeContext);
            } catch (RuntimeException exception) {
                logger.warn("[Agent链路持久化] 事件处理失败，eventType={}, exception={}",
                        event == null ? null : event.getType(), exception.getClass().getSimpleName());
            }
            return Mono.just(event);
        });
    }

    /** 按事件类型创建或结束对应的 Span。 */
    private void dispatch(HookEvent event, AgentRuntimeContext runtimeContext) {
        String traceId = traceIdOf(runtimeContext);
        if (event instanceof PreCallEvent preCallEvent) {
            startAgent(preCallEvent, runtimeContext);
        } else if (event instanceof PostCallEvent postCallEvent) {
            finishAgent(postCallEvent, traceId, "SUCCESS", null);
        } else if (event instanceof ErrorEvent errorEvent) {
            finishAgent(errorEvent, traceId, "FAILED", errorEvent.getError());
        } else if (event instanceof PreReasoningEvent preReasoningEvent) {
            startReasoning(preReasoningEvent, traceId);
        } else if (event instanceof PostReasoningEvent postReasoningEvent) {
            finishReasoning(postReasoningEvent, traceId);
        } else if (event instanceof PreActingEvent preActingEvent) {
            startActing(preActingEvent, traceId);
        } else if (event instanceof PostActingEvent postActingEvent) {
            finishActing(postActingEvent, traceId);
        } else if (event instanceof PreSummaryEvent preSummaryEvent) {
            startSummary(preSummaryEvent, traceId);
        } else if (event instanceof PostSummaryEvent postSummaryEvent) {
            finishSummary(postSummaryEvent, traceId);
        }
    }

    /** 插入 Agent 调用 Span，并关联同名 Agent as Tool 的父调用。 */
    private void startAgent(PreCallEvent event, AgentRuntimeContext runtimeContext) {
        Agent agent = event.getAgent();
        if (agent == null) {
            return;
        }

        String traceId = traceIdOf(runtimeContext);
        if (traceId == null) {
            logger.debug("[Agent链路持久化] 缺少运行时 traceId，跳过 Span 持久化，agentName={}", agent.getName());
            return;
        }
        PendingAgentTool parentTool = claimAgentTool(traceId, agent.getName());
        AgentExecution execution = new AgentExecution(traceId, runtimeContext, agent);
        if (parentTool == null) {
            execution.agentSpan = newSpan(execution, "AGENT", agent.getName(), null);
            insert(execution.agentSpan);
        } else {
            // Agent as Tool 与父级工具调用共用一条 Span，保留工具调用 ID 并避免重复统计一层。
            execution.agentSpan = parentTool.span;
            execution.agentSpan.setSpanType("AGENT");
            execution.agentSpan.setOperationName(agent.getName());
            execution.agentSpan.setAgentId(agent.getAgentId());
            execution.agentSpan.setAgentName(agent.getName());
            execution.agentSpan.setInputMessages(toJson(event.getInputMessages()));
            execution.agentSpan.setInputMessageCount(sizeOf(event.getInputMessages()));
            execution.agentSpan.setInputChars(charsOf(event.getInputMessages()));
            update(execution.agentSpan);
        }
        executions.put(executionKey(traceId, agent.getAgentId()), execution);

        if (parentTool != null) {
            parentTool.claimed = true;
        }
    }

    /** 将 Agent Span 更新为最终状态，并清理该实例关联的运行态数据。 */
    private void finishAgent(HookEvent event, String traceId, String status, Throwable error) {
        String agentId = agentIdOf(event);
        AgentExecution execution = traceId == null || agentId == null ? null
                : executions.remove(executionKey(traceId, agentId));
        if (execution == null) {
            return;
        }
        Msg result = event instanceof PostCallEvent postCallEvent ? postCallEvent.getFinalMessage() : null;
        execution.agentSpan.setStatus(status);
        execution.agentSpan.setEndTime(now());
        execution.agentSpan.setDurationMs(elapsedMs(execution.agentSpan));
        execution.agentSpan.setOutputChars(result == null ? null : (long) textLength(result));
        execution.agentSpan.setOutputMessage(messageToJson(result));
        execution.agentSpan.setErrorSummary(errorSummary(error));
        update(execution.agentSpan);

        // Agent 异常时，将仍未结束的子 Span 标记失败，避免留下永久 RUNNING 记录。
        if ("FAILED".equals(status)) {
            execution.reasoningSpans.forEach(span -> finishPending(span, "FAILED", error));
            execution.summarySpans.forEach(span -> finishPending(span, "FAILED", error));
            execution.pendingTools.values().forEach(pending -> finishPending(pending.span, "FAILED", error));
        }
        removePendingTools(execution);
    }

    /** 为一轮模型推理插入 Span，并保存本轮实际输入消息快照。 */
    private void startReasoning(PreReasoningEvent event, String traceId) {
        AgentExecution execution = executionOf(event, traceId);
        if (execution == null) {
            return;
        }
        AgentTraceEntity span = newSpan(execution, "MODEL", event.getModelName(), null);
        span.setModelName(event.getModelName());
        span.setIteration(execution.iteration.incrementAndGet());
        span.setInputMessages(toJson(event.getInputMessages()));
        span.setInputMessageCount(sizeOf(event.getInputMessages()));
        span.setInputChars(charsOf(event.getInputMessages()));
        insert(span);
        execution.reasoningSpans.add(span);
    }

    /** 保存模型推理耗时、Token 用量和输出字符数。 */
    private void finishReasoning(PostReasoningEvent event, String traceId) {
        AgentExecution execution = executionOf(event, traceId);
        AgentTraceEntity span = execution == null ? null : execution.reasoningSpans.poll();
        if (span == null) {
            return;
        }
        Msg result = event.getReasoningMessage();
        ChatUsage usage = result == null ? null : result.getChatUsage();
        span.setStatus("SUCCESS");
        span.setEndTime(now());
        span.setDurationMs(elapsedMs(span));
        span.setOutputChars(result == null ? null : (long) textLength(result));
        span.setOutputMessage(messageToJson(result));
        if (usage != null) {
            span.setInputTokens((long) usage.getInputTokens());
            span.setOutputTokens((long) usage.getOutputTokens());
        }
        update(span);
    }

    /** 暂存工具开始事件，等待判断它是普通工具还是 Agent as Tool 调用。 */
    private void startActing(PreActingEvent event, String traceId) {
        AgentExecution execution = executionOf(event, traceId);
        ToolUseBlock toolUse = event.getToolUse();
        if (execution == null || toolUse == null || toolUse.getId() == null) {
            return;
        }
        PendingAgentTool pending = new PendingAgentTool(execution, toolUse, now());
        pending.span = newSpan(execution, "TOOL", toolUse.getName(), null);
        pending.span.setToolName(toolUse.getName());
        pending.span.setToolCallId(toolUse.getId());
        pending.span.setToolInput(toJson(toolUse.getInput()));
        insert(pending.span);
        execution.pendingTools.put(toolUse.getId(), pending);
        pendingAgentTools.computeIfAbsent(agentToolKey(execution.traceId, toolUse.getName()), key ->
                new ConcurrentLinkedQueue<>()).add(pending);
    }

    /** 持久化普通工具结果；Agent as Tool 的调用由子 Agent Span 表示，避免重复记账。 */
    private void finishActing(PostActingEvent event, String traceId) {
        AgentExecution execution = executionOf(event, traceId);
        ToolUseBlock toolUse = event.getToolUse();
        if (execution == null || toolUse == null || toolUse.getId() == null) {
            return;
        }
        PendingAgentTool pending = execution.pendingTools.remove(toolUse.getId());
        if (pending == null || pending.claimed) {
            return;
        }

        AgentTraceEntity span = pending.span;
        ToolResultBlock result = event.getToolResult();
        span.setToolResult(result == null ? null : contentBlocksJson(result.getOutput()));
        span.setOutputChars(result == null ? 0L : (long) charsOfBlocks(result.getOutput()));
        span.setStatus(toolStatus(result));
        span.setEndTime(now());
        span.setDurationMs(elapsedMs(span));
        if ("FAILED".equals(span.getStatus()) && result != null) {
            span.setErrorSummary(truncate(JSON.toJSONString(result.getOutput()), 1000));
        }
        update(span);
        ConcurrentLinkedQueue<PendingAgentTool> queue = pendingAgentTools.get(
                agentToolKey(execution.traceId, toolUse.getName()));
        if (queue != null) {
            queue.remove(pending);
        }
    }

    /** 为上下文总结插入 Span，并记录其输入消息。 */
    private void startSummary(PreSummaryEvent event, String traceId) {
        AgentExecution execution = executionOf(event, traceId);
        if (execution == null) {
            return;
        }
        AgentTraceEntity span = newSpan(execution, "SUMMARY", "AutoContextSummary", null);
        span.setModelName(event.getModelName());
        span.setInputMessages(toJson(event.getInputMessages()));
        span.setInputMessageCount(sizeOf(event.getInputMessages()));
        span.setInputChars(charsOf(event.getInputMessages()));
        insert(span);
        execution.summarySpans.add(span);
    }

    /** 更新上下文总结 Span 的耗时与 Token 用量。 */
    private void finishSummary(PostSummaryEvent event, String traceId) {
        AgentExecution execution = executionOf(event, traceId);
        AgentTraceEntity span = execution == null ? null : execution.summarySpans.poll();
        if (span == null) {
            return;
        }
        Msg result = event.getSummaryMessage();
        ChatUsage usage = result == null ? null : result.getChatUsage();
        span.setStatus("SUCCESS");
        span.setEndTime(now());
        span.setDurationMs(elapsedMs(span));
        span.setOutputChars(result == null ? null : (long) textLength(result));
        span.setOutputMessage(messageToJson(result));
        if (usage != null) {
            span.setInputTokens((long) usage.getInputTokens());
            span.setOutputTokens((long) usage.getOutputTokens());
        }
        update(span);
    }

    /** 创建携带链路和 Agent 信息的 Span 实体。 */
    private AgentTraceEntity newSpan(AgentExecution execution, String type, String operation,
                                     PendingAgentTool parentTool) {
        AgentTraceEntity span = new AgentTraceEntity();
        span.setTraceId(execution.traceId);
        span.setSpanId(UUID.randomUUID().toString());
        span.setParentSpanId(parentTool == null ? execution.agentSpan == null
                ? null : execution.agentSpan.getSpanId() : parentTool.execution.agentSpan.getSpanId());
        span.setSessionId(execution.sessionId);
        span.setUserId(execution.userId);
        span.setAgentId(execution.agentId);
        span.setAgentName(execution.agentName);
        span.setSpanType(type);
        span.setOperationName(operation);
        span.setStatus("RUNNING");
        span.setStartTime(parentTool == null ? now() : parentTool.startTime);
        if (parentTool != null) {
            span.setToolName(parentTool.toolUse.getName());
            span.setToolCallId(parentTool.toolUse.getId());
        }
        return span;
    }

    /** 查找 Agent 当前执行状态。 */
    private AgentExecution executionOf(HookEvent event, String traceId) {
        String agentId = agentIdOf(event);
        return traceId == null || agentId == null ? null : executions.get(executionKey(traceId, agentId));
    }

    /** 以请求链路和 Agent 实例共同隔离运行态，避免共享 Agent 并发处理时互相覆盖。 */
    private String executionKey(String traceId, String agentId) {
        return traceId + "|" + agentId;
    }

    /** 按 traceId 和 Agent 工具名称取出对应的父调用关系。 */
    private PendingAgentTool claimAgentTool(String traceId, String agentName) {
        if (agentName == null) {
            return null;
        }
        String normalizedAgentName = agentName.toLowerCase(java.util.Locale.ROOT);
        for (Map.Entry<String, ConcurrentLinkedQueue<PendingAgentTool>> entry : pendingAgentTools.entrySet()) {
            if (!entry.getKey().startsWith(traceId + "|")) {
                continue;
            }
            String toolName = entry.getKey().substring(traceId.length() + 1)
                    .toLowerCase(java.util.Locale.ROOT);
            if (normalizedAgentName.equals(toolName) || normalizedAgentName.endsWith(toolName)) {
                PendingAgentTool candidate = pollUnclaimed(entry.getValue());
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** 从指定工具队列中取出尚未被子 Agent 认领的调用。 */
    private PendingAgentTool pollUnclaimed(ConcurrentLinkedQueue<PendingAgentTool> pending) {
        PendingAgentTool candidate;
        while ((candidate = pending.poll()) != null) {
            if (!candidate.claimed) {
                return candidate;
            }
        }
        return null;
    }

    /** 清理本次 Agent 执行中未被子 Agent 认领的待处理工具关系。 */
    private void removePendingTools(AgentExecution execution) {
        execution.pendingTools.values().forEach(pending -> {
            ConcurrentLinkedQueue<PendingAgentTool> queue = pendingAgentTools.get(
                    agentToolKey(execution.traceId, pending.toolUse.getName()));
            if (queue != null) {
                queue.remove(pending);
            }
        });
        execution.pendingTools.clear();
    }

    /** 插入 Span 并隔离数据库异常，确保监控持久化不会中断 Agent 执行。 */
    private void insert(AgentTraceEntity span) {
        try {
            persistenceService.insert(span);
        } catch (RuntimeException exception) {
            logger.warn("[Agent链路持久化] Span 插入失败，traceId={}, spanId={}, exception={}",
                    span.getTraceId(), span.getSpanId(), exception.getClass().getSimpleName());
        }
    }

    /** 更新 Span 并隔离数据库异常，确保监控持久化不会中断 Agent 执行。 */
    private void update(AgentTraceEntity span) {
        try {
            persistenceService.update(span);
        } catch (RuntimeException exception) {
            logger.warn("[Agent链路持久化] Span 更新失败，traceId={}, spanId={}, exception={}",
                    span.getTraceId(), span.getSpanId(), exception.getClass().getSimpleName());
        }
    }

    /** 将未结束 Span 标记失败并写入异常摘要。 */
    private void finishPending(AgentTraceEntity span, String status, Throwable error) {
        span.setStatus(status);
        span.setEndTime(now());
        span.setDurationMs(elapsedMs(span));
        span.setErrorSummary(errorSummary(error));
        update(span);
    }

    /** 从 AgentScope 运行时上下文读取本次追踪 ID，缺失时跳过持久化。 */
    private String traceIdOf(AgentRuntimeContext runtimeContext) {
        String traceId = runtimeContext == null ? null : runtimeContext.getTraceId();
        return traceId == null || traceId.isBlank() ? null : traceId;
    }

    /** 生成 Agent as Tool 父子调用关系的查找键。 */
    private String agentToolKey(String traceId, String toolName) {
        return traceId + "|" + toolName;
    }

    /** 从 Hook 事件中提取 Agent 实例 ID。 */
    private String agentIdOf(HookEvent event) {
        Agent agent = event == null ? null : event.getAgent();
        return agent == null ? null : agent.getAgentId();
    }

    /** 将消息列表转换为稳定且可控的 JSON 快照，避免直接序列化 AgentScope 对象内部状态。 */
    private String toJson(List<Msg> messages) {
        if (messages == null) {
            return null;
        }
        JSONArray result = new JSONArray();
        for (Msg message : messages) {
            JSONObject item = new JSONObject();
            if (message != null) {
                item.put("role", message.getRole() == null ? null : message.getRole().name());
                item.put("name", message.getName());
                item.put("content", contentBlocksJsonValue(message.getContent()));
            }
            result.add(item);
        }
        return result.toJSONString();
    }

    /** 将工具入参映射序列化为 JSON，保留每次模型实际发起调用时的参数快照。 */
    private String toJson(Map<String, Object> input) {
        return input == null ? null : JSON.toJSONString(input);
    }

    /** 将单条 AgentScope 消息序列化为可持久化的 JSON 对象。 */
    private String messageToJson(Msg message) {
        if (message == null) {
            return null;
        }
        JSONObject item = new JSONObject();
        item.put("role", message.getRole() == null ? null : message.getRole().name());
        item.put("name", message.getName());
        item.put("content", contentBlocksJsonValue(message.getContent()));
        return item.toJSONString();
    }

    /** 将单个工具结果内容块列表序列化为 JSON 数组。 */
    private String contentBlocksJson(List<ContentBlock> blocks) {
        return contentBlocksJsonValue(blocks).toString();
    }

    /** 将 AgentScope 内容块转换为前端可直接展示的结构。 */
    private JSONArray contentBlocksJsonValue(List<ContentBlock> blocks) {
        JSONArray result = new JSONArray();
        if (blocks == null) {
            return result;
        }
        for (ContentBlock block : blocks) {
            JSONObject item = new JSONObject();
            if (block instanceof TextBlock textBlock) {
                item.put("type", "text");
                item.put("text", textBlock.getText());
            } else if (block instanceof ToolUseBlock toolUse) {
                item.put("type", "tool_use");
                item.put("id", toolUse.getId());
                item.put("name", toolUse.getName());
                item.put("input", toolUse.getInput());
            } else if (block instanceof ToolResultBlock toolResult) {
                item.put("type", "tool_result");
                item.put("id", toolResult.getId());
                item.put("suspended", toolResult.isSuspended());
                item.put("output", contentBlocksJsonValue(toolResult.getOutput()));
            } else if (block != null) {
                item.put("type", block.getClass().getSimpleName());
                item.put("value", block.toString());
            }
            result.add(item);
        }
        return result;
    }

    /** 计算消息列表的字符规模。 */
    private long charsOf(List<Msg> messages) {
        return messages == null ? 0L : messages.stream().mapToLong(this::textLength).sum();
    }

    /** 计算单条消息文本长度。 */
    private int textLength(Msg message) {
        String text = message == null ? null : message.getTextContent();
        return text == null ? 0 : text.length();
    }

    /** 计算内容块中的文本长度。 */
    private int charsOfBlocks(List<ContentBlock> blocks) {
        if (blocks == null) {
            return 0;
        }
        return blocks.stream().mapToInt(block -> block instanceof TextBlock textBlock
                && textBlock.getText() != null ? textBlock.getText().length() : 0).sum();
    }

    /** 返回消息数量，空列表按零处理。 */
    private int sizeOf(List<Msg> messages) {
        return messages == null ? 0 : messages.size();
    }

    /** 根据工具结果块判断成功、失败或挂起状态。 */
    private String toolStatus(ToolResultBlock result) {
        if (result == null) {
            return "FAILED";
        }
        if (result.isSuspended()) {
            return "SUSPENDED";
        }
        String output = result.getOutput() == null ? "" : result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse("");
        return output.startsWith("Error") || output.startsWith("error") ? "FAILED" : "SUCCESS";
    }

    /** 生成脱敏范围内的异常摘要，只保留异常类型和截断后的消息。 */
    private String errorSummary(Throwable error) {
        if (error == null) {
            return null;
        }
        String message = error.getMessage();
        String summary = error.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
        return truncate(summary, 1000);
    }

    /** 截断异常或工具错误摘要以符合表字段长度限制。 */
    private String truncate(String value, int maxLength) {
        return value == null || value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /** 返回本地持久化时间。 */
    private LocalDateTime now() {
        return LocalDateTime.now();
    }

    /** 计算 Span 开始到当前时间的毫秒数。 */
    private long elapsedMs(AgentTraceEntity span) {
        return Math.max(0L, java.time.Duration.between(span.getStartTime(), now()).toMillis());
    }

    /** 保存一个 Agent 实例当前调用及其子 Span 的运行状态。 */
    private static final class AgentExecution {
        private final String traceId;
        private final Long sessionId;
        private final Long userId;
        private final String agentId;
        private final String agentName;
        private final java.util.concurrent.atomic.AtomicInteger iteration = new java.util.concurrent.atomic.AtomicInteger();
        private final ConcurrentLinkedQueue<AgentTraceEntity> reasoningSpans = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<AgentTraceEntity> summarySpans = new ConcurrentLinkedQueue<>();
        private final Map<String, PendingAgentTool> pendingTools = new ConcurrentHashMap<>();
        private AgentTraceEntity agentSpan;

        /** 构建 Agent 执行状态并复制请求维度，避免后续事件依赖外部上下文变化。 */
        private AgentExecution(String traceId, AgentRuntimeContext runtimeContext, Agent agent) {
            this.traceId = traceId;
            this.sessionId = parseLong(runtimeContext == null ? null : runtimeContext.getSessionId());
            this.userId = parseLong(runtimeContext == null ? null : runtimeContext.getUserId());
            this.agentId = agent.getAgentId();
            this.agentName = agent.getName();
        }

        /** 将运行时用户或会话标识转换为表中的数值 ID。 */
        private static Long parseLong(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Long.valueOf(value);
            } catch (NumberFormatException exception) {
                return null;
            }
        }
    }

    /** 保存 Agent as Tool 的暂存调用，供匹配子 Agent 生命周期并合并成一个 Agent Span。 */
    private static final class PendingAgentTool {
        private final AgentExecution execution;
        private final ToolUseBlock toolUse;
        private final LocalDateTime startTime;
        private AgentTraceEntity span;
        private volatile boolean claimed;

        /** 记录父 Agent、工具调用 ID 和工具启动时间。 */
        private PendingAgentTool(AgentExecution execution, ToolUseBlock toolUse, LocalDateTime startTime) {
            this.execution = execution;
            this.toolUse = toolUse;
            this.startTime = startTime;
        }
    }
}
