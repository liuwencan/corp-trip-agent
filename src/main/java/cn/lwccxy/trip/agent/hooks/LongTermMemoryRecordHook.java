package cn.lwccxy.trip.agent.hooks;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 记录长期记忆写入和检索工具调用的审计日志。 */
public class LongTermMemoryRecordHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(LongTermMemoryRecordHook.class);
    private static final String RECORD_TOOL = "recordToMemory";
    private static final String RETRIEVE_TOOL = "retrieveFromMemory";
    private static final int MAX_AUDIT_TEXT_LENGTH = 4000;

    /**
     * 在长期记忆工具调用前后记录审计信息；检索工具额外记录搜索词和返回结果。
     *
     * @param event AgentScope Hook 事件
     * @param <T> Hook 事件类型
     * @return 原始 Hook 事件
     */
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return Mono.deferContextual(contextView -> {
            if (!(event instanceof PreActingEvent) && !(event instanceof PostActingEvent)) {
                return Mono.just(event);
            }

            ToolUseBlock toolUse = event instanceof PreActingEvent preEvent
                    ? preEvent.getToolUse()
                    : ((PostActingEvent) event).getToolUse();
            ToolResultBlock toolResult = event instanceof PostActingEvent postEvent
                    ? postEvent.getToolResult()
                    : null;
            String toolName = toolUse == null
                    ? (toolResult == null ? null : toolResult.getName())
                    : toolUse.getName();
            if (!isLongTermMemoryTool(toolName)) {
                return Mono.just(event);
            }

            AgentRuntimeContext runtimeContext = contextView.getOrDefault("agentRuntimeContext", null);
            String phase = event instanceof PreActingEvent ? "开始" : "结束";
            String status = event instanceof PreActingEvent
                    ? "执行中"
                    : determineStatus(toolResult);
            Integer itemCount = event instanceof PreActingEvent
                    ? getItemCount(toolName, toolUse == null ? null : toolUse.getInput())
                    : null;
            String searchKeywords = RETRIEVE_TOOL.equals(toolName) && event instanceof PreActingEvent
                    ? getSearchKeywords(toolUse == null ? null : toolUse.getInput())
                    : null;
            String searchResult = RETRIEVE_TOOL.equals(toolName) && event instanceof PostActingEvent
                    ? sanitizeForLog(extractText(toolResult == null ? null : toolResult.getOutput()))
                    : null;

            // 检索内容按用户要求纳入审计，写入内容仍不记录，避免额外复制个人记忆正文。
            logger.info("[长期记忆审计] phase={}, status={}, traceId={}, userId={}, sessionId={}, agentName={}, toolName={}, toolCallId={}, itemCount={}, searchKeywords={}, searchResult={}",
                    phase, status,
                    runtimeContext == null ? null : runtimeContext.getTraceId(),
                    runtimeContext == null ? null : runtimeContext.getUserId(),
                    runtimeContext == null ? null : runtimeContext.getSessionId(),
                    event.getAgent() == null ? null : event.getAgent().getName(),
                    toolName,
                    toolUse == null ? (toolResult == null ? null : toolResult.getId()) : toolUse.getId(),
                    itemCount,
                    searchKeywords,
                    searchResult);
            return Mono.just(event);
        });
    }

    /**
     * 判断事件是否属于 AgentScope 的长期记忆写入或检索工具。
     *
     * @param toolName 工具名称
     * @return 属于长期记忆工具时返回 true
     */
    private boolean isLongTermMemoryTool(String toolName) {
        return RECORD_TOOL.equals(toolName) || RETRIEVE_TOOL.equals(toolName);
    }

    /**
     * 获取写入条目数或检索关键词数，不记录列表内容。
     *
     * @param toolName 长期记忆工具名称
     * @param input 工具输入参数
     * @return 输入列表条目数；参数缺失或格式异常时返回 null
     */
    private Integer getItemCount(String toolName, Map<String, Object> input) {
        if (input == null) {
            return null;
        }
        String fieldName = RECORD_TOOL.equals(toolName) ? "content" : "keywords";
        Object value = input.get(fieldName);
        return value instanceof List<?> items ? items.size() : null;
    }

    /**
     * 获取长期记忆检索的搜索词，并限制长度和日志换行符。
     *
     * @param input 工具输入参数
     * @return 可写入审计日志的搜索词；参数缺失或格式异常时返回 null
     */
    private String getSearchKeywords(Map<String, Object> input) {
        if (input == null || !(input.get("keywords") instanceof List<?> keywords)) {
            return null;
        }
        String joinedKeywords = keywords.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        return sanitizeForLog(joinedKeywords);
    }

    /**
     * 转义换行符并截断审计文本，避免注入额外日志行或产生过大的日志记录。
     *
     * @param value 原始检索文本
     * @return 可写入单条日志的文本
     */
    private String sanitizeForLog(String value) {
        if (value == null) {
            return null;
        }
        String sanitized = value.replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
        if (sanitized.length() <= MAX_AUDIT_TEXT_LENGTH) {
            return sanitized;
        }
        return sanitized.substring(0, MAX_AUDIT_TEXT_LENGTH) + "...[已截断]";
    }

    /**
     * 根据 AgentScope 工具结果判断长期记忆操作是否成功。
     *
     * @param toolResult 工具执行结果
     * @return 成功、失败或结果未知
     */
    private String determineStatus(ToolResultBlock toolResult) {
        if (toolResult == null) {
            return "结果缺失";
        }
        String output = extractText(toolResult.getOutput());
        if (output.startsWith("Error recording memory:") || output.startsWith("Error retrieving memory:")) {
            return "失败";
        }
        if (output.startsWith("Successfully recorded to long-term memory")
                || output.startsWith("No content provided to record")
                || output.startsWith("No valid content to record")
                || output.startsWith("No keywords provided for search")
                || output.startsWith("No relevant memories found")
                || output.contains("<long_term_memory>")) {
            return "成功";
        }
        return "结果未知";
    }

    /**
     * 从工具结果中提取文本用于状态识别，不将文本写入日志。
     *
     * @param output 工具输出内容块
     * @return 拼接后的文本；没有文本时返回空字符串
     */
    private String extractText(List<ContentBlock> output) {
        if (output == null) {
            return "";
        }
        // AgentScope 记忆工具将结果包装为文本块，状态识别只检查固定前缀或标记。
        return output.stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .filter(Objects::nonNull)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }
}
