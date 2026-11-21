package cn.lwccxy.trip.business.chat;

import cn.lwccxy.trip.agent.AgentRegistry;
import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.agent.context.AgentRuntimeContextHolder;
import cn.lwccxy.trip.agent.enums.IntentType;
import cn.lwccxy.trip.agent.enums.ModelPricingEnum;
import cn.lwccxy.trip.agent.enums.RecognitionLevel;
import cn.lwccxy.trip.agent.intent.IntentRecognizer;
import cn.lwccxy.trip.agent.store.activeagent.ActiveAgentSessionStore;
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
import cn.lwccxy.trip.agent.enums.SseEventEnum;
import cn.lwccxy.trip.agent.sse.SseEmitterRegistry;
import cn.lwccxy.trip.agent.sse.SseEmitterSender;
import cn.lwccxy.trip.common.utils.SpringContextUtil;
import com.alibaba.fastjson2.JSON;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.*;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import reactor.core.publisher.Flux;
import reactor.util.context.Context;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;


/** 负责执行主智能体流式对话，并将事件推送给调用方。 */
@Component
@Slf4j
public class ChatAgentPipeline {
    private final Logger logger = LoggerFactory.getLogger(ChatAgentPipeline.class);
    private final Long SSE_TIMEOUT = 18000000L; // 30分钟
    private final ConversationService conversations;
    private final ConversationCoordinator coordinator;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final TransactionTemplate transactions;
    private final SseEmitterRegistry sseEmitterRegistry;
    private final AgentRegistry agentRegistry;
    private final Model lightWeightModel;
    private final IntentRecognizer intentRecognizer;
    private final ExecutorService chatTaskExecutor;
    private final ActiveAgentSessionStore activeAgentSessionStore;

    /** 全部扁平化的继续信号词集合（小写形式，"ok/yes/confirm/continue" 已统一为小写） */
    public static final Set<String> ALL = Set.of(
            "确定", "确认", "提交", "继续", "是的", "好的", "对", "好", "修改", "补充", "不对", "取消", "重新", "再",
            "ok", "yes", "confirm", "continue", "修改一下", "补充一下", "重新来", "再来");

    /** 查询改写最多读取的历史消息数量，避免整段会话进入改写模型上下文。 */
    private static final int INTENT_HISTORY_MESSAGE_LIMIT = 12;

    /** 构造会话流水线，并明确注入用于标题生成的轻量级模型。 */
    public ChatAgentPipeline(ConversationService conversations, ConversationCoordinator coordinator,
                             ConversationMapper conversationMapper, MessageMapper messageMapper,
                             TransactionTemplate transactions, SseEmitterRegistry sseEmitterRegistry,
                             AgentRegistry agentRegistry,
                             @Qualifier("lightWeightModel") Model lightWeightModel,
                             IntentRecognizer intentRecognizer,
                             @Qualifier("chatTaskExecutor") ExecutorService chatTaskExecutor,
                             ActiveAgentSessionStore activeAgentSessionStore) {
        this.conversations = conversations;
        this.coordinator = coordinator;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.transactions = transactions;
        this.sseEmitterRegistry = sseEmitterRegistry;
        this.agentRegistry = agentRegistry;
        this.lightWeightModel = lightWeightModel;
        this.intentRecognizer = intentRecognizer;
        this.chatTaskExecutor = chatTaskExecutor;
        this.activeAgentSessionStore = activeAgentSessionStore;
    }


    /** 为当前用户执行一次会话，先保存用户消息，再通过服务端事件推送智能体响应。 */
    public SseEmitter execute(Long userId, Long conversationId, String question) {
        String traceId = UUID.randomUUID().toString();
        logger.info("[ChatAgentPipeline] Pipeline开始执行, 用户原始问题为：{}, userId: {}, sessionId: {}, traceId: {}", question, userId, conversationId ,traceId);

        // 1. 问题校验
        validateQuestion(question);
        // 2. 会话创建
        ConversationEntity conversation = ensureConversation(userId, conversationId, question);
        // 新建会话时请求参数中的 conversationId 仍为空，后续消息和 Agent session 必须使用数据库生成的 ID。
        Long activeConversationId = conversation.getId();
        // 在保存本轮用户消息前读取历史，避免把当前问题重复作为历史上下文传给查询改写模型。
        List<IntentRecognizer.ConversationTurn> intentHistory = loadIntentHistory(userId, activeConversationId);
        // 3. 问题持久化
        append(userId, activeConversationId, MessageRole.USER, question, null);
        // 5. 注册SSE
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        sseEmitterRegistry.register(activeConversationId.toString(), emitter);

        // 构建运行时上下文并沿用会话已有的 AgentScope session。
        AgentRuntimeContext agentRuntimeContext = AgentRuntimeContext.build()
                .sessionId(activeConversationId.toString())
                .userId(userId.toString())
                .traceId(traceId)
                .builder();
        try {
            chatTaskExecutor.execute(() -> {
                // 将Agent运行期间上下文放到ThreadLocal里面, 用于传递到Agent的构建中
                // 因为Agent是多例模式, 每次通过applicationContext.get()方法获取其实都是创建一个新的对象
                AgentRuntimeContextHolder.set(agentRuntimeContext);
                try {
                    ReActAgent agent;

                    // 用户发送的消息
                    List<Msg> inputMsgs = new ArrayList<>();
                    inputMsgs.add(Msg.builder().role(MsgRole.USER).textContent(question).build());

                    // ============ 判断是否快速路由 =============
                    // 1. 判断用户问题是否包含一些继续等快速路由的关键词, 如果包含的话, 再去检查当前session会话下是否有上一轮对话活跃的agent存在, 存在的话直接调度对应的活跃Agent
                    // 2. 如果用户问题没有命中关键词, 或者是上一轮对话不存在活跃Agent。这种情况是直接走 意图识别 + MasterAgent 编排重新去做推理
                    String activeAgent = activeAgentSessionStore.getActiveAgent(String.valueOf(conversationId));
                    if(activeAgent != null && isContinue(question)){
                        // 走直接调度活跃Agent
                        logger.info("[ChatAgentPipeline] 命中继续信号的关键词且上一轮对话中存在活跃Agent, 走直接调度方式, 目标智能体为: {} | traceId: {} | userId: {} | sessionId: {}", activeAgent, traceId, userId, conversationId);
                        activeAgent = activeAgent.substring(0, 1).toLowerCase() + activeAgent.substring(1);
                        agent = SpringContextUtil.getBean(activeAgent, ReActAgent.class);
                    }else{
                        // ============ 意图识别 =============
                        // 推送意图开始事件
                        // 先完成意图识别，再创建主 Agent，确保本轮路由信息稳定。
                        SseEmitterSender.send(emitter, SseEventEnum.INTENT_RECOGNITION_START, traceId);
                        // 在构建 prototype MasterAgent 前完成识别，使本轮 Agent 能拿到稳定的路由提示。
                        IntentRecognizer.IntentRecognitionResult intentResult = intentRecognition(
                                question, intentHistory, emitter, traceId);
                        // 在结束事件前单独回传识别摘要，前端无需等待主 Agent 完成即可展示路由结果。
                        sendIntentRecognitionResult(emitter, traceId, intentResult);
                        // 推送意图结束事件
                        SseEmitterSender.send(emitter, SseEventEnum.INTENT_RECOGNITION_END, traceId);

                        // 主智能体创建阶段会读取 ThreadLocal，并将上下文复制到本次 Agent 专属 Hook 中。
                        agent = SpringContextUtil.getBean("masterAgent", ReActAgent.class);

                        buildMasterAgentMsgs(inputMsgs, intentResult);
                    }

                    // 记录首Token时长：以提交 Agent 流请求的时刻为起点，统计首个可见文本 Token 的端到端等待时长。
                    long agentStartNanos = System.nanoTime();
                    AtomicBoolean firstTokenRecorded = new AtomicBoolean();
                    AtomicBoolean terminal = new AtomicBoolean();
                    // 将识别结果作为一次性系统消息传给主 Agent，原始用户消息仍单独保留，避免改写用户语义或污染历史。
                    Flux<Event> events = agent.stream(inputMsgs, StreamOptions.defaults());
                    events.doOnSubscribe(subscription ->
                                    agentRegistry.register(activeConversationId.toString(), agent))
                            .doFinally(signal ->
                                    agentRegistry.unregister(activeConversationId.toString(), agent))
                            .subscribe(event -> {
                                if (terminal.get()) {
                                    return;
                                }
                                if (event.getType() == EventType.REASONING) {
                                    // 推理事件
                                    String delta = event.getMessage().getTextContent();
                                    if (event.isLast()) {
                                        // 如果是最后一个事件不进行推送, 防止前端重复渲染
                                        return;
                                    }
                                    if (delta != null && !delta.isEmpty()) {
                                        // 记录首Token时长
                                        recordFirstTokenLatency(activeConversationId, agentStartNanos,
                                                firstTokenRecorded);
                                        SseEmitterSender.send(emitter, SseEventEnum.DELTA, delta);
                                    }
                                } else if (event.getType() == EventType.AGENT_RESULT) {
                                    Msg result = event.getMessage();
                                    GenerateReason reason = result == null ? null : result.getGenerateReason();
                                    // 某些模型不会发送中间推理事件，首次文本可能只出现在最终 Agent 结果中。
                                    if (result != null && result.getTextContent() != null
                                            && !result.getTextContent().isBlank()) {
                                        recordFirstTokenLatency(activeConversationId, agentStartNanos,
                                                firstTokenRecorded);
                                    }

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
                                    if (!terminal.compareAndSet(false, true)) {
                                        return;
                                    }

                                    // 最终消息覆盖可能包含中间轮次内容的增量缓存，作为持久化正文的权威结果。
                                    String resultText = result.getTextContent();
                                    log.info("本轮对话完毕, Agent最终输出的结果是： {}", resultText);
                                    // 仅在收到合法的最终 assistant 结果后落库并发送完成事件。
                                    append(userId, activeConversationId, MessageRole.ASSISTANT,
                                            resultText, modelName());
//                                    Map<String, Object> completePayload = new LinkedHashMap<>();
//                                    completePayload.put("conversationId", activeConversationId);
//                                    completePayload.put("intents", toIntentPayload(intentResult));
//                                    completePayload.put("isMultiIntent",
//                                            intentResult != null && intentResult.multiIntent());
//                                    // 完成事件回传意图识别阶段生成的问题改写；Map 可保留未改写时的 null 值。
//                                    completePayload.put("rewrittenQuestion",
//                                            intentResult == null ? null : intentResult.rewrittenQuestion());
//                                    completePayload.put("rewriteReason",
//                                            intentResult == null ? null : intentResult.rewriteReason());
//                                    sendEvent(emitter, SseEventEnum.COMPLETE, completePayload);
                                    emitter.complete();
                                }
                            }, throwable -> {
                                log.warn("[ChatAgentPipeline] Agent conversation {} failed",
                                        activeConversationId, throwable);
                                if (terminal.compareAndSet(false, true)) {
                                    sendError(emitter);
                                    emitter.complete();
                                }
                            }, () -> { }, Context.of("agentRuntimeContext", agentRuntimeContext));
                } catch (Exception exception) {
                    logger.warn("[ChatAgentPipeline] 后台执行会话失败, conversationId={}",
                            activeConversationId, exception);
                    sendError(emitter);
                    emitter.complete();
                } finally {
                    // Agent 已经捕获上下文，及时清理请求线程中的 ThreadLocal，避免线程池复用导致上下文泄漏。
                    AgentRuntimeContextHolder.clear();
                }
            });
        } catch (RuntimeException exception) {
            logger.warn("[ChatAgentPipeline] 无法提交会话后台任务, conversationId={}",
                    activeConversationId, exception);
            sendError(emitter);
            emitter.complete();
        }

        return emitter;
    }

    /**
     * 用于判断用户问题是否命中快速路由等关键词
     * @param question
     * @return
     */
    public boolean isContinue(String question){
        if (question == null || question.isBlank()) {
            return false;
        }

        String lower = question.toLowerCase();
        // 命中任意一个继续信号就返回true
        return ALL.stream().anyMatch(lower::equals);
    }

    /**
     * 中断当前用户会话中正在运行的聊天任务。
     *
     * @param userId 当前用户标识
     * @param conversationId 要中断的会话标识
     */
    public void interrupt(Long userId, Long conversationId) {
        // 1. 校验会话归属, 防止越权中断其他用户的会话
        conversations.owned(userId, conversationId);
        // 2. 从Map里面去获取agent实例, 调用提供的中断方法 interrupt() 执行中断
        // Registry 内部负责查询会话当前的 Agent 并发出中断请求。
        boolean interrupt = agentRegistry.interrupt(conversationId.toString());

        if (interrupt) {
            // 本机命中时由当前请求线程完成通知；跨节点命中则由 Redis 监听节点调用同一收尾逻辑。
            notifyInterrupted(conversationId.toString());
        }
    }

    /**
     * 向指定会话发送中断完成提示并结束对应 SSE 连接。
     *
     * @param sessionId 会话标识
     */
    public void notifyInterrupted(String sessionId) {
        SseEmitter emitter = sseEmitterRegistry.getEmitter(sessionId);
        // 中断后通过增量事件提示用户，并在发送后结束当前 SSE 连接。
        SseEmitterSender.send(emitter, SseEventEnum.DELTA,
                "已经成功中断Agent执行, 还有什么可以帮助到您的吗？");
        if (emitter != null) {
            emitter.complete();
        }
    }

    /**
     * 在用户消息后分别追加意图识别结果和问题改写结果系统消息。
     *
     * @param inputMsgs 本轮发送给主 Agent 的消息列表，用户消息应已先行加入
     * @param intentResult 意图识别结果；为空时按未知意图处理
     */
    private void buildMasterAgentMsgs(List<Msg> inputMsgs,
                                     IntentRecognizer.IntentRecognitionResult intentResult) {
        RecognitionLevel level = intentResult == null || intentResult.level() == null
                ? RecognitionLevel.NONE : intentResult.level();
        boolean multiIntent = intentResult != null && intentResult.multiIntent();

        // 意图识别与查询改写分别使用独立消息；意图消息用 JSON 保留多意图边界。
        inputMsgs.add(Msg.builder()
                .role(MsgRole.SYSTEM)
                .textContent("意图识别结果（仅作路由参考）：" + JSON.toJSONString(Map.of(
                        "intents", toIntentPayload(intentResult),
                        "isMultiIntent", multiIntent)))
                .build());
        inputMsgs.add(Msg.builder()
                .role(MsgRole.SYSTEM)
                .textContent("查询改写结果（JSON）：" + JSON.toJSONString(toRewritePayload(intentResult)))
                .build());
    }

    /**
     * 记录当前 Agent 对话的首个可见文本 Token 时长。
     *
     * @param conversationId 会话标识
     * @param startNanos Agent 流请求开始时间
     * @param recorded 首 Token 是否已经记录的并发标记
     */
    private void recordFirstTokenLatency(Long conversationId, long startNanos,
                                         AtomicBoolean recorded) {
        // SSE 事件在异步线程中回调，使用 CAS 保证并发事件只产生一条首 Token 指标日志。
        if (!recorded.compareAndSet(false, true)) {
            return;
        }
        long costMs = (System.nanoTime() - startNanos) / 1_000_000;
        logger.info("[Agent首Token指标] conversationId={}, 首Token时长={}ms", conversationId, costMs);
    }

    /**
     * 编排当前问题的分层意图识别，并记录识别结果供后续主 Agent 路由参考。
     *
     * <p>识别器内部负责 L0/L1/L2、查询改写和 L3 兜底；流水线只负责调用边界、异常隔离和审计日志。
     * 意图识别失败不能阻断正常对话，因此异常时使用 UNKNOWN 结果继续交给 MasterAgent。</p>
     *
     * @param question 用户原始问题
     * @param history 当前会话历史
     * @param emitter 当前请求的 SSE 连接
     * @param traceId 当前请求追踪标识
     * @return 识别结果；识别异常时返回 UNKNOWN 的 L3 结果
     */
    private IntentRecognizer.IntentRecognitionResult intentRecognition(
            String question, List<IntentRecognizer.ConversationTurn> history,
            SseEmitter emitter, String traceId) {
        long startNanos = System.nanoTime();
        try {
            // 将识别器内部的阶段信息转成 SSE 事件，同时保持识别器不依赖 Web 层协议。
            IntentRecognizer.IntentRecognitionResult result = intentRecognizer.recognize(
                    question, history, progress -> sendIntentProgress(emitter, traceId, progress));

            long costMs = (System.nanoTime() - startNanos) / 1_000_000;
            RecognitionLevel level = result == null || result.level() == null
                    ? RecognitionLevel.NONE : result.level();
            boolean multiIntent = result != null && result.multiIntent();
            logger.info("[ChatAgentPipeline意图编排] 层级={}, 意图列表={}, 多意图={}, 改写问题={}, 改写原因={}, 耗时={}ms",
                    level, toIntentPayload(result), multiIntent,
                    result == null ? null : result.rewrittenQuestion(),
                    result == null ? null : result.rewriteReason(), costMs);
            return result == null
                    ? new IntentRecognizer.RecognitionResultL3(false, IntentType.UNKNOWN)
                    : result;
        } catch (Exception exception) {
            long costMs = (System.nanoTime() - startNanos) / 1_000_000;
            logger.warn("[ChatAgentPipeline意图编排] 意图识别失败，降级到 MasterAgent，耗时={}ms",
                    costMs, exception);
            // 识别异常时明确通知前端已采用默认路由，避免进度停留在最后一个未完成阶段。
            sendIntentProgress(emitter, traceId, new IntentRecognizer.IntentRecognitionProgress(
                    "recognition_fallback", "completed", "意图识别暂不可用，已切换到默认路由",
                    null, null, null));
            return new IntentRecognizer.RecognitionResultL3(false, IntentType.UNKNOWN);
        }
    }

    /**
     * 将识别阶段转换为前端可消费的 SSE 进度事件。
     *
     * @param emitter 当前请求的 SSE 连接
     * @param traceId 当前请求追踪标识
     * @param progress 意图识别阶段信息
     */
    private void sendIntentProgress(SseEmitter emitter, String traceId,
                                    IntentRecognizer.IntentRecognitionProgress progress) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("traceId", traceId);
        payload.put("stage", progress.stage());
        payload.put("status", progress.status());
        payload.put("message", progress.message());
        if (progress.passName() != null) {
            payload.put("pass", progress.passName());
        }
        if (progress.level() != null) {
            payload.put("level", progress.level().name());
        }
        // 改写结果只附加在改写完成事件中，避免其他阶段的 payload 出现无关字段。
        if ("question_rewrite".equals(progress.stage()) && "completed".equals(progress.status())) {
            payload.put("rewrittenQuestion", progress.rewrittenQuestion());
        }
        SseEmitterSender.send(emitter, SseEventEnum.INTENT_RECOGNITION_PROGRESS, payload);
    }

    /**
     * 在意图识别结束事件前回传本轮识别摘要。
     *
     * @param emitter 当前请求的 SSE 连接
     * @param traceId 当前请求追踪标识
     * @param intentResult 意图识别结果
     */
    private void sendIntentRecognitionResult(SseEmitter emitter, String traceId,
                                             IntentRecognizer.IntentRecognitionResult intentResult) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("traceId", traceId);
        payload.put("stage", "recognition_result");
        payload.put("status", "completed");
        payload.put("message", "意图识别完成");
        payload.put("level", intentResult.level() == null ? RecognitionLevel.NONE.name()
                : intentResult.level().name());
        payload.put("intents", toIntentPayload(intentResult));
        payload.put("isMultiIntent", intentResult.multiIntent());
        SseEmitterSender.send(emitter, SseEventEnum.INTENT_RECOGNITION_PROGRESS, payload);
    }

    /**
     * 将识别结果转换为按识别顺序排列的意图编码列表。
     *
     * @param intentResult 意图识别结果
     * @return 按识别顺序排列的意图编码列表
     */
    private List<String> toIntentPayload(
            IntentRecognizer.IntentRecognitionResult intentResult) {
        if (intentResult == null || intentResult.intents() == null) {
            return List.of();
        }
        return intentResult.intents().stream()
                .map(intent -> intent.originalIntent().getTargetIntent())
                .toList();
    }

    /**
     * 将改写问题与改写原因转换为应用层统一传递的字段结构。
     *
     * @param intentResult 意图识别结果
     * @return 包含改写问题和原因的映射；未改写时两个字段均为空
     */
    private Map<String, String> toRewritePayload(
            IntentRecognizer.IntentRecognitionResult intentResult) {
        Map<String, String> rewritePayload = new LinkedHashMap<>();
        rewritePayload.put("rewrittenQuestion",
                intentResult == null ? null : intentResult.rewrittenQuestion());
        rewritePayload.put("reason", intentResult == null ? null : intentResult.rewriteReason());
        return rewritePayload;
    }

    /**
     * 读取当前会话最近消息并转换为查询改写所需的历史轮次。
     *
     * @param userId 当前用户标识
     * @param conversationId 当前会话标识
     * @return 按时间正序排列的有限历史消息
     */
    private List<IntentRecognizer.ConversationTurn> loadIntentHistory(Long userId, Long conversationId) {
        try {
            List<MessageEntity> recentMessages = messageMapper.recentOwned(
                    userId, conversationId, INTENT_HISTORY_MESSAGE_LIMIT);
            if (recentMessages == null || recentMessages.isEmpty()) {
                return List.of();
            }
            // Mapper 按倒序返回最近消息，改写模型按正序阅读更符合会话上下文。
            List<IntentRecognizer.ConversationTurn> history = new ArrayList<>(recentMessages.size());
            for (int index = recentMessages.size() - 1; index >= 0; index--) {
                MessageEntity message = recentMessages.get(index);
                history.add(new IntentRecognizer.ConversationTurn(message.getRole(), message.getContent()));
            }
            return List.copyOf(history);
        } catch (Exception exception) {
            // 历史上下文属于改写增强信息，读取失败时退化为无历史识别，不能阻断当前会话。
            logger.warn("[意图查询改写] 会话历史读取失败，conversationId={}，将使用当前问题继续识别",
                    conversationId, exception);
            return List.of();
        }
    }

    /**
     * 构造主 Agent 的本轮输入消息，并注入不可变的路由提示。
     *
     * @param userMessage 原始用户消息
     * @param intentResult 意图识别结果
     * @return 包含路由提示和原始用户消息的消息列表
     */
    private List<Msg> buildAgentMessages(Msg userMessage,
                                         IntentRecognizer.IntentRecognitionResult intentResult) {
        IntentType intentType = intentResult == null || intentResult.intentType() == null
                ? IntentType.UNKNOWN : intentResult.intentType();
        RecognitionLevel level = intentResult == null || intentResult.level() == null
                ? RecognitionLevel.NONE : intentResult.level();
        boolean multiIntent = intentResult != null && intentResult.multiIntent();

        // 路由提示只引用枚举值，不拼接用户输入，避免把用户内容提升为系统指令。
        String routingHint = String.format(
                "本轮请求的前置意图识别结果（仅作为路由参考，不替代用户原话）：识别层级=%s，意图=%s，目标智能体=%s，多意图=%s。"
                        + "请以用户原始问题为最终依据；多意图请求需覆盖全部独立诉求，未知意图由你继续判断。",
                level, intentType, intentType.getTargetAgent(), multiIntent);
        return List.of(
                Msg.builder().role(MsgRole.SYSTEM).textContent(routingHint).build(),
                userMessage);
    }

    /** 使用轻量级模型异步生成会话标题，并仅在标题仍为默认值时回写。 */
    private void asyncUpdateConversationTitle(Long userId, Long conversationId, String question, String expectedTitle) {
        // 标题生成放到后台任务执行，避免模型响应时间影响本轮对话的请求建立。
        Thread.ofVirtual().name("VirtualThread-updateTitle").start(() -> {
            // 任务开始计时
            long startNanos = System.nanoTime();
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

        String traceId = UUID.randomUUID().toString();
        // 构建运行时上下文并沿用会话已有的 AgentScope session，同时关联本次恢复请求的 traceId。
        AgentRuntimeContext agentRuntimeContext = AgentRuntimeContext.build()
                .sessionId(conversationId.toString())
                .userId(userId.toString())
                .traceId(traceId)
                .builder();
        // Agent 构建阶段通过线程本地上下文捕获当前恢复请求的身份与追踪信息。
        AgentRuntimeContextHolder.set(agentRuntimeContext);
        ReActAgent masterAgent;
        try {
            masterAgent = SpringContextUtil.getBean("masterAgent", ReActAgent.class);
        } finally {
            AgentRuntimeContextHolder.clear();
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        sseEmitterRegistry.register(conversationId.toString(), emitter);

        Flux<Event> events = masterAgent.stream(List.of(toolResultMsg));
        AtomicBoolean terminal = new AtomicBoolean();
        events.doOnSubscribe(subscription -> agentRegistry.register(conversationId.toString(), masterAgent))
                .doFinally(signal -> agentRegistry.unregister(conversationId.toString(), masterAgent))
                .subscribe(event -> {
            if (terminal.get()) {
                return;
            }
            if (event.getType() == EventType.REASONING) {
                String delta = event.getMessage().getTextContent();
                if (delta != null && !delta.isEmpty()) {
                    SseEmitterSender.send(emitter, SseEventEnum.DELTA, delta);
                }
            } else if (event.getType() == EventType.AGENT_RESULT) {
                Msg result = event.getMessage();
                GenerateReason reason = result == null ? null : result.getGenerateReason();

                if (isAskUserSuspension(result)) {
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

                if (!terminal.compareAndSet(false, true)) {
                    return;
                }
                // Agent 最终结果（包括 AgentScope 生成的中断回复）是持久化正文的权威内容。
                String resultText = result.getTextContent();
                log.info("本轮对话完毕, Agent最终输出的结果是： {}", resultText);
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
        }, () -> { }, Context.of("agentRuntimeContext", agentRuntimeContext));

        return emitter;
    }
}
