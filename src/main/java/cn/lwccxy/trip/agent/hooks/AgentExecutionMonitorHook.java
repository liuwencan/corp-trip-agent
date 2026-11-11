package cn.lwccxy.trip.agent.hooks;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 全链路执行监控 Hook。
 *
 * <p>按「一次 Agent 调用」为维度，串联记录以下关键节点：调用开始、LLM 推理开始与结束、
 * 工具执行开始与结束、上下文压缩总结、调用结束和异常。每个节点输出耗时，
 * 调用结束再输出一次汇总，便于定位慢推理、慢工具、上下文膨胀和调用失败原因。</p>
 *
 * <p>设计约束：</p>
 * <ul>
 *   <li>本 Hook 只做观测，不修改事件内容，任何内部异常都必须被吞掉，不能影响 Agent 主流程。</li>
 *   <li>Hook 实例在多个会话之间共享，因此运行状态按 Agent 实例 ID 隔离，
 *       并在 {@link PostCallEvent} 或 {@link ErrorEvent} 时清理，避免长期驻留内存。</li>
 *   <li>日志只输出消息条数、字符数和工具名，不输出用户正文；正文仅在 DEBUG 级别按长度截断输出。</li>
 * </ul>
 */
public class AgentExecutionMonitorHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(AgentExecutionMonitorHook.class);

    /**
     * Hook 优先级：数值越小越早执行。
     * 取 50 是为了晚于凭证注入类 Hook（10/30，保证观测到注入后的工具参数），
     * 早于进度推送 Hook（100，便于统计推送前的真实耗时）。
     */
    private static final int HOOK_PRIORITY = 50;

    /** Reactor Context 中运行时上下文的固定 key，与其余 Hook 保持一致。 */
    private static final String RUNTIME_CONTEXT_KEY = "agentRuntimeContext";

    /** 单条消息在 DEBUG 日志中的最大输出字符数，避免日志被超长工具结果撑爆。 */
    private static final int MAX_DEBUG_CHARS = 2000;

    /** 运行中 Agent 调用状态的并发上限，超过时输出告警以便排查状态未清理的问题。 */
    private static final int MAX_TRACKED_EXECUTIONS = 2000;

    /**
     * 正在执行中的 Agent 调用状态，key 为 Agent 实例 ID。
     * Agent 为 prototype 作用域，实例 ID 可区分同一会话内主智能体与各子智能体。
     */
    private final Map<String, ExecutionTrace> traces = new ConcurrentHashMap<>();

    /**
     * 创建无状态的 Agent 全链路监控 Hook。
     */
    public AgentExecutionMonitorHook() {
    }

    /**
     * 返回 Hook 优先级，保证在进度推送 Hook 之前观测到各节点耗时。
     *
     * @return Hook 优先级
     */
    @Override
    public int priority() {
        return HOOK_PRIORITY;
    }

    /**
     * 按事件类型分发链路监控逻辑，始终返回原始事件以保证 Agent 继续执行。
     *
     * @param event AgentScope Hook 事件
     * @param <T> Hook 事件类型
     * @return 未经修改的原始 Hook 事件
     */
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return Mono.deferContextual(contextView -> {
            // 监控属于旁路能力，任何统计或日志异常都不能中断 Agent 主流程。
            try {
                AgentRuntimeContext runtimeContext = contextView.getOrDefault(RUNTIME_CONTEXT_KEY, null);
                dispatch(event, runtimeContext);
            } catch (RuntimeException exception) {
                logger.warn("[Agent链路监控] 记录执行事件失败，eventType={}, exception={}",
                        event == null ? null : event.getType(), exception.getClass().getSimpleName());
            }
            return Mono.just(event);
        });
    }

    /**
     * 按事件类型调用对应的节点处理方法。
     *
     * @param event Hook 事件
     * @param runtimeContext 当前请求的运行时上下文，可能为 null
     */
    private void dispatch(HookEvent event, AgentRuntimeContext runtimeContext) {
        if (event instanceof PreCallEvent preCallEvent) {
            handlePreCall(preCallEvent, runtimeContext);
        } else if (event instanceof PreReasoningEvent preReasoningEvent) {
            handlePreReasoning(preReasoningEvent, runtimeContext);
        } else if (event instanceof PostReasoningEvent postReasoningEvent) {
            handlePostReasoning(postReasoningEvent, runtimeContext);
        } else if (event instanceof PreActingEvent preActingEvent) {
            handlePreActing(preActingEvent, runtimeContext);
        } else if (event instanceof PostActingEvent postActingEvent) {
            handlePostActing(postActingEvent, runtimeContext);
        } else if (event instanceof PreSummaryEvent preSummaryEvent) {
            handlePreSummary(preSummaryEvent, runtimeContext);
        } else if (event instanceof PostSummaryEvent postSummaryEvent) {
            handlePostSummary(postSummaryEvent, runtimeContext);
        } else if (event instanceof PostCallEvent postCallEvent) {
            handlePostCall(postCallEvent, runtimeContext);
        } else if (event instanceof ErrorEvent errorEvent) {
            handleError(errorEvent, runtimeContext);
        }
        // 其余事件（推理分片、工具分片等）只跳过不记录，避免刷屏并放大日志开销。
    }

    /**
     * 记录 Agent 调用开始节点，并初始化本次调用的计时与累计状态。
     *
     * @param event 调用开始事件
     * @param runtimeContext 当前请求的运行时上下文
     */
    private void handlePreCall(PreCallEvent event, AgentRuntimeContext runtimeContext) {
        ExecutionTrace trace = createTrace(event, runtimeContext);
        List<Msg> inputMessages = event.getInputMessages();
        logger.info("[Agent链路监控] 节点=调用开始, {}, 输入消息数={}, 输入字符数={}",
                describe(trace), sizeOf(inputMessages), charsOf(inputMessages));
        debugMessages(event, "调用输入", inputMessages);
    }

    /**
     * 记录 LLM 推理开始节点，并保存推理计时起点。
     *
     * <p>输入消息中同时包含系统提示词、历史对话和工具结果，字符数可直接用于判断上下文是否膨胀。</p>
     *
     * @param event 推理开始事件
     * @param runtimeContext 当前请求的运行时上下文
     */
    private void handlePreReasoning(PreReasoningEvent event, AgentRuntimeContext runtimeContext) {
        ExecutionTrace trace = getOrCreateTrace(event, runtimeContext);
        trace.reasoningStartNanos = System.nanoTime();
        List<Msg> inputMessages = event.getInputMessages();
        logger.info("[Agent链路监控] 节点=推理开始, {}, 轮次={}, 模型={}, 输入消息数={}, 输入字符数={}",
                describe(trace), trace.reasonings + 1, event.getModelName(),
                sizeOf(inputMessages), charsOf(inputMessages));
        // 推理输入是 Token 消耗的主体，仅在 DEBUG 下展开，便于做提示词和上下文优化。
        debugMessages(event, "推理输入", inputMessages);
    }

    /**
     * 记录 LLM 推理结束节点，汇总本次推理的耗时与 Token 用量。
     *
     * @param event 推理结束事件
     * @param runtimeContext 当前请求的运行时上下文
     */
    private void handlePostReasoning(PostReasoningEvent event, AgentRuntimeContext runtimeContext) {
        ExecutionTrace trace = getOrCreateTrace(event, runtimeContext);
        long durationMs = trace.elapsedSince(trace.reasoningStartNanos);
        trace.reasoningStartNanos = 0L;
        trace.reasonings++;

        Msg reasoningMessage = event.getReasoningMessage();
        ChatUsage usage = reasoningMessage == null ? null : reasoningMessage.getChatUsage();
        // 逐轮累加真实 Token 用量，最终消息只携带最后一轮用量，不能用于全程统计。
        if (usage != null) {
            trace.inputTokens += usage.getInputTokens();
            trace.outputTokens += usage.getOutputTokens();
        }
        int toolUseCount = reasoningMessage == null ? 0 : reasoningMessage.getContentBlocks(ToolUseBlock.class).size();

        logger.info("[Agent链路监控] 节点=推理结束, {}, 轮次={}, 模型={}, 耗时={}ms, 输入Token={}, 输出Token={}, 总Token={}, 模型耗时={}s, 输出字符数={}, 计划工具调用数={}",
                describe(trace), trace.reasonings, event.getModelName(), durationMs,
                usage == null ? "未知" : usage.getInputTokens(),
                usage == null ? "未知" : usage.getOutputTokens(),
                usage == null ? "未知" : usage.getTotalTokens(),
                usage == null ? "未知" : usage.getTime(),
                reasoningMessage == null ? 0 : textLength(reasoningMessage),
                toolUseCount);
        // 推理文本可能包含业务结论，仅在 DEBUG 下输出并截断。
        if (reasoningMessage != null) {
            logger.debug("[Agent链路监控] {}, 推理输出={}", describe(trace),
                    truncate(reasoningMessage.getTextContent(), MAX_DEBUG_CHARS));
        }
    }

    /**
     * 记录工具执行开始节点，按工具调用 ID 保存计时起点以支持并行工具调用。
     *
     * @param event 工具执行开始事件
     * @param runtimeContext 当前请求的运行时上下文
     */
    private void handlePreActing(PreActingEvent event, AgentRuntimeContext runtimeContext) {
        ExecutionTrace trace = getOrCreateTrace(event, runtimeContext);
        ToolUseBlock toolUse = event.getToolUse();
        if (toolUse == null) {
            return;
        }
        trace.actingStartNanosByToolUseId.put(toolUse.getId(), System.nanoTime());
        logger.info("[Agent链路监控] 节点=工具开始, {}, 工具名={}, 工具调用ID={}, 参数键={}",
                describe(trace), toolUse.getName(), toolUse.getId(),
                toolUse.getInput() == null ? "无" : toolUse.getInput().keySet());
        // 工具参数可能包含用户输入和凭证，只在 DEBUG 下输出并截断。
        logger.debug("[Agent链路监控] {}, 工具参数={}", describe(trace), toolUse.getInput());
    }

    /**
     * 记录工具执行结束节点，输出耗时、结果状态、输出字符数和挂起标记。
     *
     * @param event 工具执行结束事件
     * @param runtimeContext 当前请求的运行时上下文
     */
    private void handlePostActing(PostActingEvent event, AgentRuntimeContext runtimeContext) {
        ExecutionTrace trace = getOrCreateTrace(event, runtimeContext);
        ToolUseBlock toolUse = event.getToolUse();
        ToolResultBlock toolResult = event.getToolResult();
        String toolUseId = toolUse == null ? null : toolUse.getId();
        Long startNanos = toolUseId == null ? null : trace.actingStartNanosByToolUseId.remove(toolUseId);
        long durationMs = startNanos == null ? -1L : (System.nanoTime() - startNanos) / 1_000_000;
        trace.toolCalls++;

        boolean suspended = toolResult != null && toolResult.isSuspended();
        logger.info("[Agent链路监控] 节点=工具结束, {}, 工具名={}, 工具调用ID={}, 耗时={}ms, 状态={}, 挂起={}, 输出字符数={}",
                describe(trace),
                toolUse == null ? null : toolUse.getName(),
                toolUseId,
                durationMs < 0 ? "未知" : durationMs,
                toolResultStatus(toolResult),
                suspended,
                toolResult == null ? 0 : charsOfBlocks(toolResult.getOutput()));
        if (toolResult != null) {
            logger.debug("[Agent链路监控] {}, 工具输出={}", describe(trace),
                    truncate(joinText(toolResult.getOutput()), MAX_DEBUG_CHARS));
        }
    }

    /**
     * 记录上下文压缩总结开始节点，用于观察自动上下文管理的触发时机。
     *
     * @param event 总结开始事件
     * @param runtimeContext 当前请求的运行时上下文
     */
    private void handlePreSummary(PreSummaryEvent event, AgentRuntimeContext runtimeContext) {
        ExecutionTrace trace = getOrCreateTrace(event, runtimeContext);
        trace.summaryStartNanos = System.nanoTime();
        List<Msg> inputMessages = event.getInputMessages();
        logger.info("[Agent链路监控] 节点=上下文压缩开始, {}, 当前轮次={}, 最大轮次={}, 模型={}, 待压缩消息数={}, 待压缩字符数={}",
                describe(trace), event.getCurrentIteration(), event.getMaxIterations(), event.getModelName(),
                sizeOf(inputMessages), charsOf(inputMessages));
    }

    /**
     * 记录上下文压缩总结结束节点，输出压缩耗时和压缩后的文本长度。
     *
     * @param event 总结结束事件
     * @param runtimeContext 当前请求的运行时上下文
     */
    private void handlePostSummary(PostSummaryEvent event, AgentRuntimeContext runtimeContext) {
        ExecutionTrace trace = getOrCreateTrace(event, runtimeContext);
        long durationMs = trace.elapsedSince(trace.summaryStartNanos);
        trace.summaryStartNanos = 0L;
        trace.summaries++;

        Msg summaryMessage = event.getSummaryMessage();
        ChatUsage usage = summaryMessage == null ? null : summaryMessage.getChatUsage();
        // 压缩本身也是一次真实 LLM 调用，其 Token 必须计入本次调用总量。
        if (usage != null) {
            trace.inputTokens += usage.getInputTokens();
            trace.outputTokens += usage.getOutputTokens();
        }
        logger.info("[Agent链路监控] 节点=上下文压缩结束, {}, 耗时={}ms, 摘要字符数={}, 输入Token={}, 输出Token={}",
                describe(trace), durationMs, summaryMessage == null ? 0 : textLength(summaryMessage),
                usage == null ? "未知" : usage.getInputTokens(),
                usage == null ? "未知" : usage.getOutputTokens());
    }

    /**
     * 记录 Agent 调用结束节点并输出整次调用的汇总指标，随后清理运行状态。
     *
     * <p>暂停等待用户确认时也会触发调用结束，汇总展示的是截至当前的实际消耗；
     * 恢复执行后由新的调用开始事件重新建立状态。</p>
     *
     * @param event 调用结束事件
     * @param runtimeContext 当前请求的运行时上下文
     */
    private void handlePostCall(PostCallEvent event, AgentRuntimeContext runtimeContext) {
        ExecutionTrace trace = getOrCreateTrace(event, runtimeContext);
        long totalDurationMs = trace.elapsedSince(trace.startNanos);
        Msg finalMessage = event.getFinalMessage();
        logger.info("[Agent链路监控] 节点=调用结束, {}, 总耗时={}ms, 推理轮次={}, 工具调用次数={}, 上下文压缩次数={}, 输入Token={}, 输出Token={}, 总Token={}, 最终回复字符数={}",
                describe(trace), totalDurationMs, trace.reasonings, trace.toolCalls, trace.summaries,
                trace.inputTokens, trace.outputTokens, trace.inputTokens + trace.outputTokens,
                finalMessage == null ? 0 : textLength(finalMessage));
        // 调用结束后移除状态，避免共享 Hook 中残留已完成执行的计时数据。
        traces.remove(traceKey(event));
    }

    /**
     * 记录 Agent 执行异常并清理运行状态，避免异常路径下状态泄漏。
     *
     * @param event 异常事件
     * @param runtimeContext 当前请求的运行时上下文
     */
    private void handleError(ErrorEvent event, AgentRuntimeContext runtimeContext) {
        ExecutionTrace trace = getOrCreateTrace(event, runtimeContext);
        long totalDurationMs = trace.elapsedSince(trace.startNanos);
        Throwable error = event.getError();
        // 只记录异常类型和信息摘要，避免把完整堆栈和敏感参数写入审计日志。
        logger.error("[Agent链路监控] 节点=执行异常, {}, 总耗时={}ms, 推理轮次={}, 工具调用次数={}, 异常类型={}, 异常信息={}",
                describe(trace), totalDurationMs, trace.reasonings, trace.toolCalls,
                error == null ? null : error.getClass().getName(),
                error == null ? null : truncate(error.getMessage(), MAX_DEBUG_CHARS));
        traces.remove(traceKey(event));
    }

    /**
     * 为一次新的 Agent 调用创建状态记录，已存在时覆盖。
     *
     * @param event 调用开始事件
     * @param runtimeContext 当前请求的运行时上下文
     * @return 新建的执行状态
     */
    private ExecutionTrace createTrace(HookEvent event, AgentRuntimeContext runtimeContext) {
        // 状态数量异常增长通常意味着调用结束事件缺失，输出告警便于排查，但不阻断业务。
        if (traces.size() > MAX_TRACKED_EXECUTIONS) {
            logger.warn("[Agent链路监控] 运行状态数量异常，当前={}，可能存在未收到调用结束事件的执行",
                    traces.size());
        }
        String key = traceKey(event);
        ExecutionTrace trace = new ExecutionTrace(
                runtimeContext == null ? null : runtimeContext.getTraceId(),
                runtimeContext == null ? null : runtimeContext.getUserId(),
                runtimeContext == null ? null : runtimeContext.getSessionId(),
                agentIdOf(event),
                agentNameOf(event));
        traces.put(key, trace);
        return trace;
    }

    /**
     * 获取已有的执行状态；状态缺失时补建，保证乱序或缺失开始事件时仍能记录后续节点。
     *
     * @param event 当前事件
     * @param runtimeContext 当前请求的运行时上下文
     * @return 执行状态
     */
    private ExecutionTrace getOrCreateTrace(HookEvent event, AgentRuntimeContext runtimeContext) {
        return traces.computeIfAbsent(traceKey(event), key -> new ExecutionTrace(
                runtimeContext == null ? null : runtimeContext.getTraceId(),
                runtimeContext == null ? null : runtimeContext.getUserId(),
                runtimeContext == null ? null : runtimeContext.getSessionId(),
                agentIdOf(event),
                agentNameOf(event)));
    }

    /**
     * 生成执行状态的唯一键。
     *
     * <p>优先使用 Agent 实例 ID；实例 ID 缺失时退回「会话 + Agent 名称」，
     * 保证同一会话内主智能体与子智能体的状态不会互相覆盖。</p>
     *
     * @param event Hook 事件
     * @return 执行状态唯一键
     */
    private String traceKey(HookEvent event) {
        String agentId = agentIdOf(event);
        if (agentId != null && !agentId.isBlank()) {
            return agentId;
        }
        return Objects.toString(agentNameOf(event), "unknown");
    }

    /**
     * 获取事件所属 Agent 的实例 ID。
     *
     * @param event Hook 事件
     * @return Agent 实例 ID；Agent 缺失时返回 null
     */
    private String agentIdOf(HookEvent event) {
        Agent agent = event == null ? null : event.getAgent();
        return agent == null ? null : agent.getAgentId();
    }

    /**
     * 获取事件所属 Agent 的名称。
     *
     * @param event Hook 事件
     * @return Agent 名称；缺失时返回 unknown
     */
    private String agentNameOf(HookEvent event) {
        Agent agent = event == null ? null : event.getAgent();
        return agent == null || agent.getName() == null ? "unknown" : agent.getName();
    }

    /**
     * 生成审计日志中使用的执行标识，统一附带链路、用户和会话维度。
     *
     * @param trace 执行状态
     * @return 可直接拼接到日志的标识文本
     */
    private String describe(ExecutionTrace trace) {
        return "traceId=" + trace.traceId
                + ", userId=" + trace.userId
                + ", sessionId=" + trace.sessionId
                + ", agentName=" + trace.agentName
                + ", agentId=" + trace.agentId;
    }

    /**
     * 计算消息列表的字符数，覆盖文本、工具调用参数和工具结果。
     *
     * @param messages 消息列表
     * @return 总字符数；列表为空时返回 0
     */
    private int charsOf(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Msg message : messages) {
            total += charsOf(message);
        }
        return total;
    }

    /**
     * 计算单条消息的字符数，优先统计所有内容块，无内容块时退回文本内容。
     *
     * @param message 消息
     * @return 字符数
     */
    private int charsOf(Msg message) {
        if (message == null) {
            return 0;
        }
        List<ContentBlock> blocks = message.getContent();
        if (blocks == null || blocks.isEmpty()) {
            return textLength(message);
        }
        return charsOfBlocks(blocks);
    }

    /**
     * 计算内容块列表的字符数。
     *
     * @param blocks 内容块列表
     * @return 字符数
     */
    private int charsOfBlocks(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock textBlock) {
                String text = textBlock.getText();
                total += text == null ? 0 : text.length();
            } else if (block instanceof ToolUseBlock toolUse) {
                total += toolUse.getInput() == null ? 0 : toolUse.getInput().toString().length();
            } else if (block instanceof ToolResultBlock toolResult) {
                total += charsOfBlocks(toolResult.getOutput());
            }
        }
        return total;
    }

    /**
     * 获取消息文本长度，空内容按 0 处理。
     *
     * @param message 消息
     * @return 文本字符数
     */
    private int textLength(Msg message) {
        String text = message == null ? null : message.getTextContent();
        return text == null ? 0 : text.length();
    }

    /**
     * 获取集合大小，空集合按 0 处理。
     *
     * @param messages 消息列表
     * @return 列表大小
     */
    private int sizeOf(List<Msg> messages) {
        return messages == null ? 0 : messages.size();
    }

    /**
     * 拼接工具输出中的文本块，仅用于 DEBUG 级别观测。
     *
     * @param blocks 输出内容块
     * @return 拼接后的文本；无文本时返回空字符串
     */
    private String joinText(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock textBlock && textBlock.getText() != null) {
                text.append(textBlock.getText());
            }
        }
        return text.toString();
    }

    /**
     * 判断工具结果状态，区分挂起、错误和正常返回。
     *
     * @param toolResult 工具结果
     * @return 中文状态描述
     */
    private String toolResultStatus(ToolResultBlock toolResult) {
        if (toolResult == null) {
            return "结果缺失";
        }
        if (toolResult.isSuspended()) {
            return "挂起等待确认";
        }
        String output = joinText(toolResult.getOutput());
        // AgentScope 工具异常通常以 Error 前缀的文本返回，命中时标记失败便于统计错误率。
        if (output.startsWith("Error") || output.startsWith("error")) {
            return "失败";
        }
        return "成功";
    }

    /**
     * 在 DEBUG 级别输出消息明细，包含角色和截断后的内容。
     *
     * @param event Hook 事件，用于取得 Agent 名称
     * @param stage 阶段说明
     * @param messages 消息列表
     */
    private void debugMessages(HookEvent event, String stage, List<Msg> messages) {
        if (!logger.isDebugEnabled() || messages == null || messages.isEmpty()) {
            return;
        }
        String agentName = agentNameOf(event);
        StringBuilder builder = new StringBuilder();
        builder.append("[Agent链路监控] agentName=").append(agentName)
                .append(", 阶段=").append(stage)
                .append(", 消息数=").append(messages.size());
        for (int index = 0; index < messages.size(); index++) {
            Msg message = messages.get(index);
            builder.append("\n  msg[").append(index).append("] role=")
                    .append(message.getRole())
                    .append(", name=").append(message.getName())
                    .append(", 字符数=").append(charsOf(message))
                    .append(", 内容=").append(truncate(renderMessage(message), MAX_DEBUG_CHARS));
        }
        logger.debug(builder.toString());
    }

    /**
     * 渲染消息内容用于 DEBUG 输出，覆盖文本、工具调用和工具结果块。
     *
     * @param message 消息
     * @return 渲染后的文本
     */
    private String renderMessage(Msg message) {
        if (message == null) {
            return "";
        }
        List<ContentBlock> blocks = message.getContent();
        if (blocks == null || blocks.isEmpty()) {
            return Objects.toString(message.getTextContent(), "");
        }
        StringBuilder builder = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock textBlock) {
                builder.append(textBlock.getText());
            } else if (block instanceof ToolUseBlock toolUse) {
                builder.append("[tool_use name=").append(toolUse.getName())
                        .append(", id=").append(toolUse.getId())
                        .append(", input=").append(toolUse.getInput()).append(']');
            } else if (block instanceof ToolResultBlock toolResult) {
                builder.append("[tool_result id=").append(toolResult.getId())
                        .append(", output=").append(joinText(toolResult.getOutput())).append(']');
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    /**
     * 截断过长文本，避免单条日志体量失控。
     *
     * @param value 原始文本
     * @param maxChars 最大保留字符数
     * @return 截断后的文本；入参为空时返回空字符串
     */
    private String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars) + "...[已截断]";
    }

    /**
     * 单次 Agent 调用的运行状态，保存计时起点与累计指标。
     *
     * <p>同一 Agent 实例的事件由同一执行链路串行处理，因此字段使用普通类型即可；
     * 工具调用起点按工具调用 ID 分开保存，兼容一次推理产生多个并行工具调用的场景。</p>
     */
    private static final class ExecutionTrace {

        private final String traceId;
        private final String userId;
        private final String sessionId;
        private final String agentId;
        private final String agentName;
        private final long startNanos;

        private volatile long reasoningStartNanos;
        private volatile long summaryStartNanos;

        private long reasonings;
        private long toolCalls;
        private long summaries;
        private long inputTokens;
        private long outputTokens;

        /** 工具调用 ID 到开始时间的映射，工具结束事件到达后立即移除。 */
        private final Map<String, Long> actingStartNanosByToolUseId = new ConcurrentHashMap<>();

        /**
         * 创建执行状态并记录调用开始时间。
         *
         * @param traceId 链路追踪 ID
         * @param userId 用户 ID
         * @param sessionId 会话 ID
         * @param agentId Agent 实例 ID
         * @param agentName Agent 名称
         */
        private ExecutionTrace(String traceId, String userId, String sessionId,
                               String agentId, String agentName) {
            this.traceId = traceId;
            this.userId = userId;
            this.sessionId = sessionId;
            this.agentId = agentId;
            this.agentName = agentName;
            this.startNanos = System.nanoTime();
        }

        /**
         * 计算从指定计时起点到当前的毫秒数。
         *
         * @param sinceNanos 起始纳秒时间；未开始计时时返回 -1
         * @return 耗时毫秒数；起始时间无效时返回 -1
         */
        private long elapsedSince(long sinceNanos) {
            if (sinceNanos <= 0L) {
                return -1L;
            }
            return (System.nanoTime() - sinceNanos) / 1_000_000;
        }
    }
}
