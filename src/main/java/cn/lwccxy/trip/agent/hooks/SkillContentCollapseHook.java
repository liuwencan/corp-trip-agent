package cn.lwccxy.trip.agent.hooks;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能正文折叠 Hook。
 *
 * <p>{@link io.agentscope.core.skill.SkillBox} 的 {@code load_skill_through_path} 工具会把整份
 * SKILL.md（数百行）作为工具结果写入 memory。由于 ReActAgent 每轮推理都会重放整个 memory，
 * 这份静态正文会在整个会话里被反复发送给模型，持续消耗 Token。</p>
 *
 * <p>本 Hook 监听 {@link PreReasoningEvent}，<b>只改写本轮发送给模型的输入消息</b>
 * （{@link PreReasoningEvent#setInputMessages(List)}），<b>不改动 memory 原文</b>，因此完全无状态：
 * 下一轮框架仍从 memory 读取完整消息，本 Hook 再按需折叠，不会造成信息永久丢失。</p>
 *
 * <p><b>折叠策略：</b>技能正文之后若已积累足够多的交互（说明模型已依据技能完成若干步骤），
 * 即折叠为占位符；占位符会提示模型在需要时重新调用 {@code load_skill_through_path} 拉取完整说明。
 * 刚加载后的近若干条消息保持完整，保证模型加载后能立即使用技能内容。</p>
 *
 * <p><b>并发安全：</b>本 Hook 不保存任何会话状态，按事件入参计算折叠结果，可被多个 Agent 和
 * 多个会话共享。</p>
 */
@Component
public class SkillContentCollapseHook implements Hook {

    private static final Logger logger = LoggerFactory.getLogger(SkillContentCollapseHook.class);

    /** 技能加载工具名，与 AgentScope 1.0.12 中 SkillBox 注册的工具名称保持一致。 */
    private static final String LOAD_SKILL_TOOL = "load_skill_through_path";

    /** 技能加载工具的参数名：技能标识。 */
    private static final String PARAM_SKILL_ID = "skillId";

    /** 技能标识的兼容参数名，兼容下划线写法。 */
    private static final String PARAM_SKILL_ID_UNDERSCORE = "skill_id";

    /** 技能加载工具的参数名：技能内资源路径。 */
    private static final String PARAM_PATH = "path";

    /** 技能文档的标准路径。 */
    private static final String DEFAULT_SKILL_PATH = "SKILL.md";

    /**
     * 技能正文之后至少还要有这么多条消息时才折叠。
     *
     * <p>保证「刚加载完」的近几轮推理仍能看到完整正文，避免加载后立刻失去说明。
     * 系统提示词中需配合懒加载指令，要求模型在真正需要时再加载技能。</p>
     */
    private static final int KEEP_RECENT_MSGS = 10;

    /**
     * 返回较高优先级。
     *
     * <p>折叠需要发生在下游观测类 Hook（如执行监控 Hook 优先级 50）之前，
     * 使日志统计的输入字符数真实反映本轮实际发送给模型的内容。</p>
     *
     * @return Hook 优先级，数值越小越先执行
     */
    @Override
    public int priority() {
        return 6;
    }

    /**
     * 处理 Hook 事件，仅在推理开始前尝试折叠历史技能正文。
     *
     * @param event AgentScope Hook 事件
     * @param <T> Hook 事件类型
     * @return 原事件；发生折叠时其输入消息已被替换
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreReasoningEvent preReasoning) {
            return handle(preReasoning).map(processed -> (T) processed);
        }
        return Mono.just(event);
    }

    /**
     * 折叠本轮输入消息中已过期的技能加载结果。
     *
     * <p>该方法不修改 memory，只替换本次推理的输入快照，因此必须保证幂等且无副作用：</p>
     * <ol>
     *     <li>消息数量不足时直接跳过，避免对短会话做无意义的遍历；</li>
     *     <li>先收集所有技能加载调用的重载指引，便于生成精确的占位符；</li>
     *     <li>仅当技能结果之后已积累 {@link #KEEP_RECENT_MSGS} 条及以上消息时才折叠；</li>
     *     <li>占位符不比原文更短时保留原文，避免出现「越折叠越大」的反效果。</li>
     * </ol>
     *
     * @param event 推理开始事件
     * @return 原事件；发生折叠时其输入消息已被替换
     */
    private Mono<PreReasoningEvent> handle(PreReasoningEvent event) {
        List<Msg> messages = event.getInputMessages();
        if (messages == null || messages.size() <= KEEP_RECENT_MSGS) {
            return Mono.just(event);
        }

        // 收集技能加载调用的 toolUseId -> 重新加载提示（skillId/path），供占位符引导模型重新拉取。
        Map<String, String> reloadHints = collectReloadHints(messages);
        if (reloadHints.isEmpty()) {
            return Mono.just(event);
        }

        String agentName = event.getAgent() == null ? null : event.getAgent().getName();
        List<Msg> newMessages = new ArrayList<>(messages.size());
        boolean changed = false;
        long savedCharacters = 0L;

        for (int i = 0; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            // ========== 从当前消息MSG提取出 load_skill 工具 ==========
            ToolResultBlock skillResult = extractSkillLoadResult(msg);
            int messagesAfter = messages.size() - 1 - i;
            // 距离当前轮次足够远的技能结果才折叠，保证刚加载的技能正文仍可被模型立即使用。
            if (skillResult != null && messagesAfter >= KEEP_RECENT_MSGS) {
                int originalLength = textLength(skillResult);
                String reloadHint = reloadHints.getOrDefault(skillResult.getId(), defaultReloadHint());
                String placeholder = buildPlaceholder(reloadHint);
                // 只有确实能减少上下文字符数时才替换，否则保持原消息不变。
                if (originalLength > placeholder.length()) {
                    newMessages.add(foldMessage(msg, agentName, skillResult, placeholder));
                    savedCharacters += (long) originalLength - placeholder.length();
                    changed = true;
                    continue;
                }
            }
            newMessages.add(msg);
        }

        if (!changed) {
            return Mono.just(event);
        }

        event.setInputMessages(newMessages);
        logger.info("[技能正文折叠] agentName={}, 已折叠历史技能正文, 约节省 {} 字符", agentName, savedCharacters);
        return Mono.just(event);
    }

    /**
     * 构造折叠后的消息。
     *
     * <p>保留原消息 id、时间戳、metadata、生成原因和其余内容块，仅替换技能结果本体的输出，
     * 以维持消息身份和模型侧工具调用关联的完整性；若消息中还包含其他内容块，
     * 则一并保留，避免误删同一消息中的其他信息。</p>
     *
     * @param original   原消息
     * @param agentName  当前 Agent 名称，用于填充消息 name
     * @param skillResult 待折叠的技能加载工具结果
     * @param placeholder 折叠占位符文本
     * @return 折叠后的新消息
     */
    private Msg foldMessage(Msg original, String agentName, ToolResultBlock skillResult, String placeholder) {
        ToolResultBlock folded = ToolResultBlock.builder()
                .id(skillResult.getId())
                .name(skillResult.getName())
                .output(TextBlock.builder().text(placeholder).build())
                .metadata(skillResult.getMetadata())
                .build();

        // 替换技能结果块，并保留同一消息中的其他内容块，保证消息结构不被破坏。
        List<ContentBlock> content = new ArrayList<>();
        List<ContentBlock> originalContent = original.getContent();
        if (originalContent != null) {
            for (ContentBlock block : originalContent) {
                if (block == skillResult) {
                    content.add(folded);
                } else {
                    content.add(block);
                }
            }
        }
        if (content.isEmpty()) {
            content.add(folded);
        }

        // 复制 metadata 后再交给 Builder：generateReason 会直接写入传入的 Map，
        // 复用原消息的 Map 实例会顺带修改 memory 中的原始消息，违背「只改本轮输入」的约定。
        Map<String, Object> metadata = original.getMetadata() == null
                ? new HashMap<>()
                : new HashMap<>(original.getMetadata());

        return Msg.builder()
                .id(original.getId())
                .name(original.getName() == null ? agentName : original.getName())
                .role(original.getRole())
                .content(content)
                .metadata(metadata)
                .timestamp(original.getTimestamp())
                .generateReason(original.getGenerateReason())
                .build();
    }

    /**
     * 遍历消息，收集所有技能加载调用的重新加载指引。
     *
     * <p>以 toolUseId 为键，便于对应的工具结果被折叠时生成精确的重新加载指引，
     * 而不是只给出一条无法直接使用的默认提示。</p>
     *
     * @param messages 本轮完整输入消息
     * @return toolUseId 到重新加载指引的映射；没有技能加载调用时返回空 Map
     */
    private Map<String, String> collectReloadHints(List<Msg> messages) {
        Map<String, String> hints = new HashMap<>();
        for (Msg msg : messages) {
            List<ContentBlock> blocks = msg.getContent();
            if (blocks == null) {
                continue;
            }
            for (ContentBlock block : blocks) {
                if (block instanceof ToolUseBlock toolUse && LOAD_SKILL_TOOL.equals(toolUse.getName())) {
                    hints.put(toolUse.getId(), buildReloadHint(toolUse));
                }
            }
        }
        return hints;
    }

    /**
     * 判断消息是否携带技能加载工具结果。
     *
     * @param msg 待检查消息
     * @return 技能加载结果块；不是技能加载结果时返回 {@code null}
     */
    private ToolResultBlock extractSkillLoadResult(Msg msg) {
        List<ContentBlock> blocks = msg.getContent();
        if (blocks == null) {
            return null;
        }
        for (ContentBlock block : blocks) {
            if (block instanceof ToolResultBlock result && LOAD_SKILL_TOOL.equals(result.getName())) {
                return result;
            }
        }
        return null;
    }

    /**
     * 统计工具结果中所有文本块的总字符数。
     *
     * @param result 工具结果
     * @return 文本总长度；没有文本时返回 0
     */
    private int textLength(ToolResultBlock result) {
        int length = 0;
        List<ContentBlock> output = result.getOutput();
        if (output != null) {
            for (ContentBlock block : output) {
                if (block instanceof TextBlock textBlock && textBlock.getText() != null) {
                    length += textBlock.getText().length();
                }
            }
        }
        return length;
    }

    /**
     * 从技能加载调用参数中解析 skillId 和 path，拼成可直接复用的重新加载指引。
     *
     * @param toolUse 技能加载工具调用
     * @return 重新加载指引；参数缺失或解析失败时返回默认指引
     */
    private String buildReloadHint(ToolUseBlock toolUse) {
        try {
            Object rawInput = toolUse.getInput();
            if (rawInput != null) {
                // AgentScope 传参可能是 Map，也可能是 JSON 字符串，两种形式都要兼容。
                JSONObject json = rawInput instanceof String text
                        ? JSON.parseObject(text)
                        : JSON.parseObject(JSON.toJSONString(rawInput));
                String skillId = json.getString(PARAM_SKILL_ID);
                if (skillId == null) {
                    skillId = json.getString(PARAM_SKILL_ID_UNDERSCORE);
                }
                String path = json.getString(PARAM_PATH);
                if (skillId != null && !skillId.isBlank()) {
                    String resolvedPath = (path != null && !path.isBlank()) ? path : DEFAULT_SKILL_PATH;
                    return String.format("%s(%s=\"%s\", %s=\"%s\")",
                            LOAD_SKILL_TOOL, PARAM_SKILL_ID, skillId, PARAM_PATH, resolvedPath);
                }
            }
        } catch (Exception exception) {
            // 参数格式异常只影响提示精度，不应中断推理流程，因此降级为 DEBUG 日志。
            logger.debug("[技能正文折叠] 解析技能加载参数失败: {}", exception.getMessage());
        }
        return defaultReloadHint();
    }

    /**
     * 构造无法获得具体参数时的默认重新加载指引。
     *
     * @return 默认重新加载指引
     */
    private String defaultReloadHint() {
        return LOAD_SKILL_TOOL + "(" + PARAM_SKILL_ID + "=\"<skill-id>\", "
                + PARAM_PATH + "=\"" + DEFAULT_SKILL_PATH + "\")";
    }

    /**
     * 构造折叠占位符。
     *
     * <p>明确告知正文已省略，并给出可直接复用的重新加载指引，避免模型误以为技能内容缺失而放弃任务。</p>
     *
     * @param reloadHint 重新加载指引
     * @return 折叠占位符文本
     */
    private String buildPlaceholder(String reloadHint) {
        return "[技能文档正文已省略以节省上下文] 该技能的说明此前已加载，你应已据此完成相应操作。"
                + "如仍需查阅完整说明，请重新调用：" + reloadHint;
    }
}
