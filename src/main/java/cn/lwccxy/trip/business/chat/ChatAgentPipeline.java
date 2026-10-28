package cn.lwccxy.trip.business.chat;

import cn.lwccxy.trip.agent.MasterAgent;
import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.agent.context.AgentRuntimeContextHolder;
import cn.lwccxy.trip.agent.enums.ModelPricingEnum;
import cn.lwccxy.trip.agent.utils.ModelTokenBillingUtil;
import cn.lwccxy.trip.business.conversation.domain.entity.ConversationEntity;
import cn.lwccxy.trip.business.conversation.domain.entity.MessageEntity;
import cn.lwccxy.trip.business.conversation.domain.form.CreateConversationForm;
import cn.lwccxy.trip.business.conversation.domain.vo.ConversationVO;
import cn.lwccxy.trip.business.conversation.domain.vo.MessageVO;
import cn.lwccxy.trip.business.conversation.enums.ConversationErrorCode;
import cn.lwccxy.trip.business.conversation.enums.MessageRole;
import cn.lwccxy.trip.business.conversation.mapper.ConversationMapper;
import cn.lwccxy.trip.business.conversation.mapper.MessageMapper;
import cn.lwccxy.trip.business.conversation.service.ConversationCoordinator;
import cn.lwccxy.trip.business.conversation.service.ConversationService;
import cn.lwccxy.trip.common.exception.BizException;
import cn.lwccxy.trip.common.response.Response;
import cn.lwccxy.trip.agent.enums.SseEventEnum;
import cn.lwccxy.trip.agent.sse.SseEmitterRegistry;
import cn.lwccxy.trip.agent.sse.SseEmitterSender;
import cn.lwccxy.trip.common.util.SpringContextUtil;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.*;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.util.context.Context;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 负责执行主智能体流式对话，并将事件推送给调用方。 */
@Component
@Slf4j
public class ChatAgentPipeline {
    private final Logger logger = LoggerFactory.getLogger(ChatAgentPipeline.class);

    private final ConversationService conversations;
    private final ConversationCoordinator coordinator;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final TransactionTemplate transactions;
    private final SseEmitterRegistry sseEmitterRegistry;
    private final Model lightWeightModel;

    /** 构造会话流水线，并明确注入用于标题生成的轻量级模型。 */
    public ChatAgentPipeline(ConversationService conversations, ConversationCoordinator coordinator,
                             ConversationMapper conversationMapper, MessageMapper messageMapper,
                             TransactionTemplate transactions, SseEmitterRegistry sseEmitterRegistry,
                             @Qualifier("lightWeightModel") Model lightWeightModel) {
        this.conversations = conversations;
        this.coordinator = coordinator;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.transactions = transactions;
        this.sseEmitterRegistry = sseEmitterRegistry;
        this.lightWeightModel = lightWeightModel;
    }


    /** 为当前用户执行一次会话，先保存用户消息，再通过服务端事件推送智能体响应。 */
    public SseEmitter execute(Long userId, Long conversationId, String question) {
        logger.info("execute 方法执行");

        // 1. 问题校验
        validateQuestion(question);
        // 2. 会话创建
        ConversationEntity conversation = ensureConversation(userId, conversationId, question);
        // 新建会话时请求参数中的 conversationId 仍为空，后续消息和 Agent session 必须使用数据库生成的 ID。
        Long activeConversationId = conversation.getId();
        // 3. 问题持久化
        MessageVO userMessage = question == null ? null : append(userId, activeConversationId, MessageRole.USER, question, null);
        // 5. 注册SSE
        SseEmitter emitter = new SseEmitter(240_000L);
        sseEmitterRegistry.register(activeConversationId.toString(), emitter);
        // 构建运行时上下文并沿用会话已有的 AgentScope session。
        AgentRuntimeContext agentRuntimeContext = AgentRuntimeContext.build()
                .sessionId(activeConversationId.toString())
                .userId(userId.toString())
                .builder();
        // 将Agent运行期间上下文放到ThreadLocal里面, 用于传递到Agent的构建中
        // 因为Agent是多例模式, 每次通过applicationContext.get()方法获取其实都是创建一个新的对象
        AgentRuntimeContextHolder.set(agentRuntimeContext);

        // ============ 意图识别 =============
        String traceId = UUID.randomUUID().toString();

        StringBuilder answer = new StringBuilder();
        AtomicBoolean receivedFinalResult = new AtomicBoolean();
        AtomicBoolean terminal = new AtomicBoolean();

        // 主智能体创建阶段会读取 ThreadLocal，并将上下文复制到本次 Agent 专属 Hook 中。
        ReActAgent masterAgent;
        try {
            masterAgent = SpringContextUtil.getBean("masterAgent", ReActAgent.class);
        } finally {
            // Agent 已经捕获上下文，及时清理请求线程中的 ThreadLocal，避免线程池复用导致上下文泄漏。
            AgentRuntimeContextHolder.clear();
        }

        // 用户发送的消息
        Msg userMsg = Msg.builder()
                .role(MsgRole.USER)
                .textContent(question)
                .build();

        Flux<Event> events = masterAgent.stream(List.of(userMsg), StreamOptions.defaults());
        events.subscribe(event -> {
            if (terminal.get()) {
                return;
            }
            // 推理事件
            if (event.getType() == EventType.REASONING) {
                String delta = event.getMessage().getTextContent();
                if (delta != null) {
                    answer.append(delta);
                }
                SseEmitterSender.send(emitter, SseEventEnum.DELTA, delta);
            } else if (event.getType() == EventType.AGENT_RESULT) {
                Msg result = event.getMessage();
                GenerateReason reason = result == null ? null : result.getGenerateReason();

                if (isAskUserSuspension(result)) {
                    // ask_user 暂停属于等待用户输入，Hook 已发送 ask 事件，此处正常结束当前 SSE。
                    if (terminal.compareAndSet(false, true)) {
                        emitter.complete();
                    }
                    return;
                }

                if (result == null || result.getRole() != MsgRole.ASSISTANT
                        || (reason != null && reason != GenerateReason.MODEL_STOP
                        && reason != GenerateReason.STRUCTURED_OUTPUT)) {
                    if (terminal.compareAndSet(false, true)) {
                        sendError(emitter);
                        emitter.complete();
                    }
                    return;
                }

                // 最终消息覆盖可能包含中间轮次内容的增量缓存，作为持久化正文的权威结果。
                String resultText = result.getTextContent();
                log.info("本轮对话完毕, Agent最终输出的结果是： {}", resultText);

                // 仅在收到合法的最终 assistant 结果后落库并发送完成事件。
                append(userId, activeConversationId, MessageRole.ASSISTANT, resultText, modelName());
                sendEvent(emitter, SseEventEnum.COMPLETE, Map.of("conversationId", activeConversationId));
                emitter.complete();
            }
        }, throwable -> {
            log.warn("[ChatAgentPipeline] Agent conversation {} failed", activeConversationId, throwable);
            if (terminal.compareAndSet(false, true)) {
                sendError(emitter);
                emitter.complete();
            }
        }, new Runnable() {
            @Override
            public void run() {

            }
        }, Context.of("agentRuntimeContext", agentRuntimeContext));

        return emitter;
    }

    /** 使用轻量级模型异步生成会话标题，并仅在标题仍为默认值时回写。 */
    private void asyncUpdateConversationTitle(Long userId, Long conversationId, String question, String expectedTitle) {
        // 标题生成放到后台任务执行，避免模型响应时间影响本轮对话的请求建立。
        Thread.ofVirtual().name("VirtualThread-updateTitle").start(() -> {
            long startNanos = System.nanoTime();          // 任务开始计时
            try {
                logger.info("[会话标题异步改写进行中] 会话ID：{}, 原始问题：{}", conversationId, question);
                List<ChatResponse> responses = lightWeightModel.stream(List.of(
                                        Msg.builder().role(MsgRole.SYSTEM)
                                                .textContent("请根据用户的问题生成简洁的中文会话标题，最多20个汉字。只返回标题文本，不加引号、序号或解释。")
                                                .build(),
                                        Msg.builder().role(MsgRole.USER).textContent(question).build()),
                                List.of(), GenerateOptions.builder().stream(false).build())
                        .collectList()
                        .block();
                // 改写后的会话标题获取
                String title = responses == null ? null : responses.stream()
                        .flatMap(response -> response.getContent().stream())
                        .filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast)
                        .map(TextBlock::getText)
                        .filter(text -> text != null && !text.isBlank())
                        .reduce("", String::concat)
                        .strip();
                // 改写的Token消耗用量
                ChatUsage usage = responses == null ? null : responses.stream()
                        .map(ChatResponse::getUsage)
                        .filter(Objects::nonNull)
                        .reduce((first, next) -> next)
                        .orElse(null);

                if (usage != null) {

                    // 费用计算
                    BigDecimal cost = ModelTokenBillingUtil.calculateCost(usage.getInputTokens(), usage.getOutputTokens(), ModelPricingEnum.QWEN_3_8_FLASH);
                    logger.info(
                            "[会话标题模型用量] conversationId={}, model={}, inputTokens={}, outputTokens={}, totalTokens={}, modelTime={}s, 总花费={}元",
                            conversationId,
                            lightWeightModel.getModelName(),
                            usage.getInputTokens(),
                            usage.getOutputTokens(),
                            usage.getTotalTokens(),
                            usage.getTime(),cost);
                }
                if (title == null || title.isBlank()) {
                    return;
                }
                // 数据库标题列最多保存200个字符，按 Unicode 码点截断以免拆开代理项。
                int titleLength = title.codePointCount(0, title.length());
                if (titleLength > 200) {
                    title = title.substring(0, title.offsetByCodePoints(0, 200));
                }

                long costMs = (System.nanoTime() - startNanos) / 1_000_000;  // ② 终点：全部完成
                logger.info("[会话标题异步改写完成] 会话ID：{} 原始问题：{}  改写后问题：{}  改写总耗时: {}ms", conversationId, question, title, costMs);
                // 条件更新验证会话归属和默认标题，防止迟到结果覆盖用户编辑。
                conversationMapper.updateTitleIfUnchanged(userId, conversationId, expectedTitle, title);
            } catch (Exception exception) {
                // 会话标题改写失败不应该影响Agent主流程, 不抛异常
                logger.warn("会话标题异步生成失败, conversationId: {}", conversationId, exception);
            }
        });
    }


    /** 校验会话归属；不存在时根据首个问题创建新的活动会话。 */
    private ConversationEntity ensureConversation(Long userId, Long conversationId, String question) {
        // 1. 查询会话是否存在
        ConversationEntity conversation = conversationId == null ? null : conversationMapper.selectById(conversationId);
        // 2. 存在, 判断会话归属
        if (conversation != null) {
            if (!userId.equals(conversation.getUserId())) {
                throw new BizException(ConversationErrorCode.RESOURCE_NOT_FOUND);
            }
            return conversation;
        }
        CreateConversationForm form = new CreateConversationForm();
        // 不存在, 先截取用户第一个问题的前五个字符, 先进行持久化
        // 后续通过异步线程利用【轻量级大模型】去做标题摘要
        form.setTitle(firstQuestionTitle(question));

        ConversationVO created = conversations.create(userId, form);
        ConversationEntity createdConversation = conversations.owned(userId, created.getId());
        // 仅本次确实新建会话时触发标题改写；已有会话在前面的分支直接返回。
        asyncUpdateConversationTitle(userId, created.getId(), question, form.getTitle());
        return createdConversation;
    }

    /** 将首个问题截取前五个字符作为会话标题。 */
    private String firstQuestionTitle(String question) {
        String normalized = question.strip();
        int characterCount = Math.min(5, normalized.codePointCount(0, normalized.length()));
        return normalized.substring(0, normalized.offsetByCodePoints(0, characterCount));
    }

    /** 校验问题内容，避免空问题或超长问题进入智能体流程。 */
    private void validateQuestion(String question) {
        if (question == null || question.isBlank() || question.length() > 20000) {
            throw new BizException(ConversationErrorCode.INVALID_PARAMETER);
        }
    }

    /** 构造允许空值的 SSE 事件数据，避免 AgentScope 可选字段导致 Map.of 抛出异常。 */
    private Map<String, Object> eventPayload(Long conversationId, Object... fields) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        for (int index = 0; index < fields.length; index += 2) {
            payload.put((String) fields[index], fields[index + 1]);
        }
        return payload;
    }

    /** 向前端发送统一的智能体失败事件，忽略已断开的客户端连接。 */
    private void sendError(SseEmitter emitter) {
//        try {
//            sendEvent(emitter, SseEventEnum.ERROR, Response.fail(ConversationErrorCode.AGENT_FAILED));
//        } catch (RuntimeException ignored) {
//            // 客户端连接已断开时无法继续发送错误事件。
//        }
    }

    /** 读取当前模型名称，用于记录 assistant 消息来源。 */
    private String modelName() {
        return SpringContextUtil.getBean("strongModel", Model.class).getModelName();
    }

    /** 判断 AgentScope 结果是否是包含 ask_user 调用的正常暂停，而非真正的执行失败。 */
    private boolean isAskUserSuspension(Msg result) {
        if (result == null) {
            return false;
        }
        GenerateReason reason = result.getGenerateReason();
        boolean expectedStop = reason == GenerateReason.TOOL_SUSPENDED
                || reason == GenerateReason.REASONING_STOP_REQUESTED
                || reason == GenerateReason.ACTING_STOP_REQUESTED;
        boolean containsAskUser = result.getContentBlocks(ToolUseBlock.class).stream()
                .anyMatch(tool -> "ask_user".equals(tool.getName()));
        return expectedStop && containsAskUser;
    }

    /** 在同一个事务中保存消息并更新所属会话的活动字段。 */
    private MessageVO append(Long userId, Long conversationId, MessageRole role, String content,
                             String modelName) {
        return transactions.execute(tx -> {
            ConversationEntity conversation = conversationMapper.lockOwned(userId, conversationId);
            if (conversation == null) {
                throw new BizException(ConversationErrorCode.RESOURCE_NOT_FOUND);
            }
            if (!"ACTIVE".equals(conversation.getStatus())) {
                throw new BizException(ConversationErrorCode.CONVERSATION_STATE_INVALID);
            }
            // 原子保存消息并递增会话计数，确保历史记录和会话统计保持一致。
            MessageEntity entity = new MessageEntity();
            entity.setConversationId(conversationId);
            entity.setUserId(userId);
            entity.setRole(role.name());
            entity.setContent(content);
            entity.setModelName(modelName);
            entity.setCreatedBy(userId);
            if (messageMapper.insert(entity) != 1
                    || conversationMapper.appended(userId, conversationId) != 1) {
                throw new IllegalStateException("Message persistence failed");
            }
            MessageEntity saved = messageMapper.lockOwned(userId, conversationId, entity.getId());
            MessageVO result = new MessageVO();
            BeanUtils.copyProperties(saved, result);
            return result;
        });
    }

    /** 发送一个命名服务端事件，并将传输异常交给订阅错误处理。 */
    private void sendEvent(SseEmitter emitter, SseEventEnum event, Object value) {
        SseEmitterSender.send(emitter, event, value);
    }

    /**
     * 恢复执行，并先记录用户对澄清问题的回复。
     *
     * @param userId 当前用户标识
     * @param conversationId 要恢复的会话标识
     * @param toolCallId 等待恢复的工具调用标识
     * @param resumeAnswer 用户对澄清问题的回复
     * @return 恢复后的流式响应
     */
    public SseEmitter resume(Long userId, Long conversationId, String toolCallId, String resumeAnswer) {
        logger.info("resume方法执行, sessionId: {}", conversationId);

        // 先持久化用户回复，确保会话历史在恢复 Agent 执行前已经完整记录。
        conversations.append(userId, conversationId, MessageRole.USER, resumeAnswer, null);

        // ========= 用户回答作为工具的执行结果 ==========
        ToolResultBlock resumeToolMsg = ToolResultBlock.builder()
                .id(toolCallId)
                .name("ask_user")
                .output(List.of(TextBlock.builder().text(resumeAnswer).build()))
                .build();

        Msg toolResultMsg = Msg.builder()
                .role(MsgRole.TOOL)
                .name("user")
                .content(resumeToolMsg)
                .build();

        // 构建运行时上下文并沿用会话已有的 AgentScope session。
        AgentRuntimeContext agentRuntimeContext = AgentRuntimeContext.build()
                .sessionId(conversationId.toString())
                .userId(userId.toString())
                .builder();
        // 将Agent运行期间上下文放到ThreadLocal里面, 用于传递到Agent的构建中
        // 因为Agent是多例模式, 每次通过applicationContext.get()方法获取其实都是创建一个新的对象
        AgentRuntimeContextHolder.set(agentRuntimeContext);

        String traceId = UUID.randomUUID().toString();

        StringBuilder answer = new StringBuilder();
        AtomicBoolean receivedFinalResult = new AtomicBoolean();
        AtomicBoolean terminal = new AtomicBoolean();


        ReActAgent masterAgent = SpringContextUtil.getBean("masterAgent", ReActAgent.class);

        SseEmitter emitter = new SseEmitter();
        sseEmitterRegistry.register(conversationId.toString(), emitter);

        Flux<Event> events = masterAgent.stream(List.of(toolResultMsg));
        events.subscribe(event -> {
            if (terminal.get()) {
                return;
            }
            // 推理事件
            if (event.getType() == EventType.REASONING) {
                String delta = event.getMessage().getTextContent();
                if (delta != null) {
                    answer.append(delta);
                }
                SseEmitterSender.send(emitter, SseEventEnum.DELTA, delta);
            } else if (event.getType() == EventType.AGENT_RESULT) {
                Msg result = event.getMessage();
                GenerateReason reason = result == null ? null : result.getGenerateReason();

                if (isAskUserSuspension(result)) {
                    // 恢复后再次需要澄清时，Hook 已发送 ask 事件，当前 SSE 也应正常结束。
                    if (terminal.compareAndSet(false, true)) {
                        emitter.complete();
                    }
                    return;
                }

                if (result == null || result.getRole() != MsgRole.ASSISTANT
                        || (reason != null && reason != GenerateReason.MODEL_STOP
                        && reason != GenerateReason.STRUCTURED_OUTPUT)) {
                    if (terminal.compareAndSet(false, true)) {
                        sendError(emitter);
                        emitter.complete();
                    }
                    return;
                }

                // 最终消息覆盖可能包含中间轮次内容的增量缓存，作为持久化正文的权威结果。
                String resultText = result.getTextContent();
                log.info("本轮对话完毕, Agent最终输出的结果是： {}", resultText);

                // 仅在收到合法的最终 assistant 结果后落库并发送完成事件。
                append(userId, conversationId, MessageRole.ASSISTANT, resultText, modelName());
                sendEvent(emitter, SseEventEnum.COMPLETE, Map.of("conversationId", conversationId));
                emitter.complete();
            }
        }, throwable -> {
            log.warn("[ChatAgentPipeline] Agent conversation {} failed", conversationId, throwable);
            if (terminal.compareAndSet(false, true)) {
                sendError(emitter);
                emitter.complete();
            }
        }, new Runnable() {
            @Override
            public void run() {

            }
        }, Context.of("agentRuntimeContext", agentRuntimeContext));

        return emitter;
    }
}
