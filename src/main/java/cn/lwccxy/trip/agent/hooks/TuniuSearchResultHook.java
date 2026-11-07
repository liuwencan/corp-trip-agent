package cn.lwccxy.trip.agent.hooks;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** 在途牛搜索结果进入模型上下文前，移除 CLI 返回的重复结构化内容。 */
@Component
public class TuniuSearchResultHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(TuniuSearchResultHook.class);
    private static final String SHELL_TOOL = "execute_shell_command";
    private static final String STDOUT_START = "<stdout>";
    private static final String STDOUT_END = "</stdout>";
    private static final Pattern SEARCH_COMMAND = Pattern.compile(
            "(?i)(?<![\\w./-])tuniu\\s+call\\s+(?:flight\\s+searchLowestPriceFlight"
                    + "|train\\s+searchLowestPriceTrain|hotel\\s+tuniuHotelSearch)(?=\\s|$)");

    /** 先精简结果，再交给进度通知和会话持久化等默认优先级的 Hook。 */
    @Override
    public int priority() {
        return 20;
    }

    /**
     * 处理搜索命令完成事件，同时替换工具结果和框架已创建的上下文消息。
     *
     * @param event AgentScope 事件
     * @param <T> 事件类型
     * @return 携带精简结果的原事件；不符合条件时原样返回
     */
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PostActingEvent actingEvent) || !isSearchCommand(actingEvent.getToolUse())) {
            return Mono.just(event);
        }
        ToolResultBlock original = actingEvent.getToolResult();
        if (original == null || original.isSuspended() || original.getOutput() == null) {
            return Mono.just(event);
        }

        // 每次调用只修改本次结果，避免共享 Hook 在并发会话间保存或串用数据。
        List<ContentBlock> output = new ArrayList<>();
        boolean changed = false;
        for (ContentBlock block : original.getOutput()) {
            if (block instanceof TextBlock textBlock) {
                String compacted = compactShellOutput(textBlock.getText());
                if (!Objects.equals(compacted, textBlock.getText())) {
                    output.add(TextBlock.builder().text(compacted).build());
                    changed = true;
                    continue;
                }
            }
            output.add(block);
        }
        if (!changed) {
            return Mono.just(event);
        }

        // 仅记录文本字符数量和调用标识，不输出命令参数或搜索结果，避免日志留存行程数据。
        int beforeCharacters = countTextCharacters(original.getOutput());
        int afterCharacters = countTextCharacters(output);
        ToolResultBlock replacement = ToolResultBlock.builder()
                .id(original.getId()).name(original.getName())
                .output(output).metadata(original.getMetadata()).build();
        actingEvent.setToolResult(replacement);
        // 1.0.12 在调用 Hook 前就构造了消息，最终写入记忆的是 toolResultMsg，必须同步替换。
        replaceContextMessage(actingEvent, replacement);
        logger.info("[途牛搜索结果精简审计] toolCallId={}, beforeCharacters={}, afterCharacters={}, savedCharacters={}",
                actingEvent.getToolUse().getId(), beforeCharacters, afterCharacters, beforeCharacters - afterCharacters);
        return Mono.just(event);
    }

    /** 按 Unicode 码点累计工具结果文本块的字符数。 */
    private int countTextCharacters(List<ContentBlock> output) {
        int count = 0;
        for (ContentBlock block : output) {
            if (block instanceof TextBlock textBlock && textBlock.getText() != null) {
                String text = textBlock.getText();
                count += text.codePointCount(0, text.length());
            }
        }
        return count;
    }

    /** 判断是否为文档约定的航班、火车或酒店搜索，兼容鉴权 Hook 注入的命令前缀。 */
    private boolean isSearchCommand(ToolUseBlock toolUse) {
        if (toolUse == null || !SHELL_TOOL.equals(toolUse.getName()) || toolUse.getInput() == null) {
            return false;
        }
        Object command = toolUse.getInput().get("command");
        return command instanceof String text && SEARCH_COMMAND.matcher(text).find();
    }

    /** 提取命令工具的标准输出并精简 JSON，保留退出码、错误输出及外围文本。 */
    private String compactShellOutput(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        int start = text.indexOf(STDOUT_START);
        if (start < 0) {
            return compactJson(text);
        }
        start += STDOUT_START.length();
        int end = text.lastIndexOf(STDOUT_END);
        if (end < start) {
            return text;
        }
        String stdout = text.substring(start, end);
        String compacted = compactJson(stdout);
        return stdout.equals(compacted) ? text : text.substring(0, start) + compacted + text.substring(end);
    }

    /**
     * 删除 CLI 结果对象中的 structuredContent，保留完整 content 和外层元数据。
     * 只有存在非空文本副本时才删除，避免仅返回结构化数据时丢失唯一业务结果。
     */
    private String compactJson(String text) {
        try {
            // 只处理完整 JSON 对象，不从日志或截断输出中猜测并抽取局部 JSON。
            if (!JSON.isValidObject(text)) {
                return text;
            }
            JSONObject root = JSON.parseObject(text);
            Object value = root.get("result");
            if (!(value instanceof JSONObject result) || !result.containsKey("structuredContent")) {
                return text;
            }
            if (!(result.get("content") instanceof JSONArray content) || !hasTextContent(content)) {
                return text;
            }
            result.remove("structuredContent");
            // 保留原结果中的 null 字段，不让重新序列化改变其他业务字段的含义。
            return JSON.toJSONString(root, JSONWriter.Feature.WriteMapNullValue);
        } catch (RuntimeException exception) {
            // 精简失败时保留原始结果，不中断搜索，也不把可能包含敏感信息的响应写入日志。
            return text;
        }
    }

    /** 确认 content 中至少保留一份可供模型读取的文本结果。 */
    private boolean hasTextContent(JSONArray content) {
        for (Object item : content) {
            if (item instanceof JSONObject block && "text".equals(block.getString("type"))
                    && block.get("text") instanceof String text && !text.isBlank()) {
                return true;
            }
        }
        return false;
    }

    /** 同步替换已生成消息中的对应工具块，保留消息标识、时间、元数据和其他内容块。 */
    private void replaceContextMessage(PostActingEvent event, ToolResultBlock replacement) {
        Msg message = event.getToolResultMsg();
        if (message == null) {
            return;
        }
        ToolUseBlock toolUse = event.getToolUse();
        List<ContentBlock> content = new ArrayList<>();
        for (ContentBlock block : message.getContent()) {
            // 框架消息中的工具块已补齐调用标识，不能直接使用可能未带标识的原始返回块。
            content.add(block instanceof ToolResultBlock result && Objects.equals(result.getId(), toolUse.getId())
                    ? replacement.withIdAndName(toolUse.getId(), toolUse.getName()) : block);
        }
        event.setToolResultMsg(Msg.builder()
                .id(message.getId()).name(message.getName()).role(message.getRole())
                .timestamp(message.getTimestamp()).metadata(message.getMetadata()).content(content).build());
    }
}
