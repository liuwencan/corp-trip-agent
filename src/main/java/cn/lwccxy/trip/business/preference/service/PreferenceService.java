package cn.lwccxy.trip.business.preference.service;

import cn.lwccxy.trip.agent.enums.ModelPricingEnum;
import cn.lwccxy.trip.agent.memory.CropTripLongTermMemory;
import cn.lwccxy.trip.agent.utils.ModelTokenBillingUtil;
import cn.lwccxy.trip.business.preference.domain.form.PreferenceSaveForm;
import cn.lwccxy.trip.business.preference.domain.vo.PreferenceCategoryVO;
import cn.lwccxy.trip.business.preference.domain.vo.PreferenceOptionVO;
import cn.lwccxy.trip.business.preference.domain.vo.UserPreferenceVO;
import cn.lwccxy.trip.common.auth.CurrentUser;
import cn.lwccxy.trip.common.response.Response;
import cn.lwccxy.trip.common.utils.RedisCacheUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.bailian.BailianLongTermMemory;
import io.agentscope.core.memory.mem0.Mem0ApiType;
import io.agentscope.core.memory.mem0.Mem0LongTermMemory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 管理当前用户的长期记忆差旅偏好。 */
@Service
public class PreferenceService {

    private static final Logger logger = LoggerFactory.getLogger(PreferenceService.class);

    private final CurrentUser currentUser;
    private final RedisCacheUtil redisCache;
    private final Model preferenceModel;
    private final String dashscopeApiKey;
    private final String memoryLibraryId;


    /**
     * 创建偏好服务并注入轻量模型和百炼长期记忆配置。
     *
     * @param currentUser 当前登录用户上下文
     * @param redisCache Redis 缓存工具，仅传给长期记忆适配器用于读写偏好缓存
     * @param preferenceModel 用于把记忆摘要结构化的轻量模型
     * @param dashscopeApiKey 百炼 API Key，配置项 corp.trip.bailian.dashscope-api
     * @param memoryLibraryId 百炼长期记忆库标识，配置项 corp.trip.bailian.memory-library-id
     */
    public PreferenceService(CurrentUser currentUser, RedisCacheUtil redisCache,
                             @Qualifier("lightWeightModel") Model preferenceModel,
                             @Value("${corp.trip.bailian.dashscope-api}") String dashscopeApiKey,
                             @Value("${corp.trip.bailian.memory-library-id}") String memoryLibraryId) {
        this.currentUser = currentUser;
        this.redisCache = redisCache;
        this.preferenceModel = preferenceModel;
        this.dashscopeApiKey = dashscopeApiKey;
        this.memoryLibraryId = memoryLibraryId;
    }

    /** 返回可供用户查看和编辑的偏好选项。 */
    public List<PreferenceCategoryVO> getPreferenceOptions() {
        return buildPreferenceOptions();
    }

    /** 从当前用户的 Mem0 长期记忆实时读取摘要，并尽可能映射为表单字段。 */
    public UserPreferenceVO getUserPreferences() {
        String userId = currentUser.get().getId().toString();
        // ========== 检索该用户的长期记忆 ============
        String summary = retrieveMemory(userId);

        UserPreferenceVO result = new UserPreferenceVO();
        result.setUserId(userId);
        result.setMemorySummary(summary);
        result.setPreferences(summary.isBlank() ? Map.of() : parseMemory(userId, summary));
        return result;
    }

    /** 校验偏好选项后写入当前用户长期记忆，并立即返回已保存的结构化值。 */
    public Response<UserPreferenceVO> saveUserPreferences(PreferenceSaveForm form) {
        Map<String, Object> normalized = normalizePreferences(form.getPreferences());
        if (normalized.isEmpty()) {
            return Response.fail("INVALID_PREFERENCES", "至少需要设置一项差旅偏好");
        }

        String userId = currentUser.get().getId().toString();
        String naturalLanguage = formatPreferencesAsText(normalized);
        LongTermMemory memory = createMemory(userId);
        Msg userMessage = buildMessage(MsgRole.USER, "请记住并在后续规划时优先使用这些差旅偏好：" + naturalLanguage);
        Msg assistantMessage = buildMessage(MsgRole.ASSISTANT, "已保存用户的差旅偏好：" + naturalLanguage);

        // 远端写入失败时直接返回失败，缓存由 CropTripLongTermMemory 在写入成功后统一失效。
        try {
            memory.record(List.of(userMessage, assistantMessage)).block();
        } catch (RuntimeException exception) {
            // 远端写入失败时保留旧缓存和旧偏好，避免接口返回成功但后续规划仍读到不一致状态。
            logger.warn("[PreferenceService] 保存当前用户长期记忆失败，userId={}, exception={}",
                    userId, exception.getClass().getSimpleName());
            return Response.fail("PREFERENCE_SAVE_FAILED", "长期记忆服务暂时不可用，偏好未保存");
        }
        logger.info("[PreferenceService] 当前用户差旅偏好已写入长期记忆，userId={}, fields={}",
                userId, normalized.size());

        UserPreferenceVO result = new UserPreferenceVO();
        result.setUserId(userId);
        result.setMemorySummary(naturalLanguage);
        result.setPreferences(normalized);
        return Response.success(result);
    }

    /** 创建绑定当前用户的 AgentScope 1.0.12 Mem0 长期记忆实例。 */
    private LongTermMemory createMemory(String userId) {
        BailianLongTermMemory longTermMemory = BailianLongTermMemory.builder()
                .apiKey(dashscopeApiKey)
                .userId(userId)
                .memoryLibraryId(memoryLibraryId)
                .metadata(Map.of("source", "corp-trip"))
                .topK(20)
                .build();

        CropTripLongTermMemory cropTripLongTermMemory = new CropTripLongTermMemory(longTermMemory, redisCache, userId);

        return cropTripLongTermMemory;
    }

    /** 使用覆盖各类差旅习惯的查询语句召回当前用户记忆。 */
    private String retrieveMemory(String userId) {
        Msg query = buildMessage(MsgRole.USER,
                "我的差旅偏好是什么？包括机票、酒店、火车、餐饮和市内交通方面的长期偏好");
        try {
            String summary = createMemory(userId).retrieve(query).block();
            return summary == null ? "" : summary.strip();
        } catch (RuntimeException exception) {
            // 记忆服务异常不暴露个人记忆正文或远端错误详情，避免泄漏到日志和接口响应。
            logger.warn("[PreferenceService] 读取当前用户长期记忆失败，userId={}, exception={}",
                    userId, exception.getClass().getSimpleName());
            return "";
        }
    }

    /**
     * 将自然语言记忆摘要解析为允许展示的结构化偏好。
     *
     * @param userId 当前用户 ID，用于模型调用审计日志
     * @param summary 长期记忆召回的自然语言摘要
     * @return 已按合法选项归一化的结构化偏好；解析失败时返回空 Map
     */
    private Map<String, Object> parseMemory(String userId, String summary) {
        // 记录模型调用起点，用于审计包含模型等待在内的端到端耗时。
        long startNanos = System.nanoTime();
        List<ChatResponse> responses = null;
        try {
            String prompt = buildParsePrompt();
            List<Msg> messages = List.of(
                    buildMessage(MsgRole.SYSTEM, prompt), // 系统提示词
                    buildMessage(MsgRole.USER, summary)); // 检索回来的摘要列表
            // 调用LLM去做解析, 输出结构化json
            responses = preferenceModel.stream(messages, null, null).collectList().block();
            String jsonText = extractText(responses).strip();
            if (jsonText.startsWith("```")) {
                jsonText = jsonText.replaceFirst("```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
            }
            JSONObject parsed = JSON.parseObject(jsonText);
            Map<String, Object> values = parsed == null ? Map.of() : parsed;
            return normalizePreferences(values);
        } catch (Exception exception) {
            // 原始摘要仍会返回给用户；结构化解析失败时仅让表单为空，避免记忆读取被模型故障阻断。
            logger.warn("[PreferenceService] 长期记忆偏好结构化解析失败，exception={}",
                    exception.getClass().getSimpleName());
            return Map.of();
        } finally {
            // 成功、解析失败和模型异常三种情况都记录一次用量，保证审计数据不遗漏。
            logModelUsage(userId, responses, startNanos);
        }
    }

    /**
     * 记录偏好结构化解析的模型调用审计信息，包括 Token 用量、估算费用和耗时。
     *
     * <p>优先使用模型服务端返回的 ChatUsage；流式响应可能只给出最后一次分片的用量，
     * 因此这里取最后一个非空用量，避免重复累计。未维护单价的模型只记录未知费用，
     * 不套用其他模型价格，防止费用统计失真。</p>
     *
     * @param userId 当前用户 ID，便于按用户排查费用归属
     * @param responses 模型响应列表；调用失败时为 null
     * @param startNanos 调用开始时间，来自 System.nanoTime()
     */
    private void logModelUsage(String userId, List<ChatResponse> responses, long startNanos) {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        // 模型名用于匹配价格枚举，未知模型不参与费用估算。
        String modelName = preferenceModel.getModelName();
        ChatUsage usage = responses == null ? null : responses.stream()
                .filter(Objects::nonNull)
                .map(ChatResponse::getUsage)
                .filter(Objects::nonNull)
                .reduce((first, next) -> next)
                .orElse(null);
        if (usage == null) {
            // 模型未返回用量时仍输出耗时和模型名，便于定位是网关未透传还是调用失败。
            logger.info("[偏好解析模型审计] userId={}, 模型={}, 输入Token=未知, 输出Token=未知, 总Token=未知, 模型耗时=未知, 估算费用=未知, 总耗时={}ms",
                    userId, modelName, elapsedMs);
            return;
        }
        // 单价只在枚举中按模型名精确匹配，未配置的模型费用记未知。
        Optional<ModelPricingEnum> pricing = findPricing(modelName);
        String costText = pricing
                .map(model -> ModelTokenBillingUtil.calculateCost(
                        usage.getInputTokens(), usage.getOutputTokens(), model) + "元")
                .orElse("未知（枚举中未配置该模型单价）");
        logger.info("[偏好解析模型审计] userId={}, 模型={}, 输入Token={}, 输出Token={}, 总Token={}, 模型耗时={}s, 估算费用={}, 总耗时={}ms",
                userId, modelName, usage.getInputTokens(), usage.getOutputTokens(), usage.getTotalTokens(),
                usage.getTime(), costText, elapsedMs);
    }

    /**
     * 按模型服务端名称查找枚举中已维护的单价。
     *
     * @param modelName 模型服务端名称
     * @return 匹配的单价枚举；模型名为空或未配置时返回空
     */
    private Optional<ModelPricingEnum> findPricing(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(ModelPricingEnum.values())
                .filter(pricing -> pricing.getModelName().equals(modelName))
                .findFirst();
    }

    /** 生成只允许从已定义字段和选项中选择的结构化抽取提示词。 */
    private String buildParsePrompt() {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是差旅偏好字段提取器。将给定的长期记忆映射为纯 JSON 对象，不输出解释或代码块。\n")
                .append("只输出记忆中明确表达且与下列选项匹配的字段；single 值为字符串，multi 值为字符串数组；未知值跳过。\n")
                .append("字段和合法选项：\n");

        for (PreferenceCategoryVO category : buildPreferenceOptions()) {
            for (PreferenceOptionVO item : category.getItems()) {
                prompt.append(item.getKey()).append(" (").append(item.getType()).append("): ")
                        .append(item.getOptions()).append('\n');
            }
        }
        return prompt.toString();
    }

    /** 从模型返回的内容块拼接文本。 */
    private String extractText(List<ChatResponse> responses) {
        if (responses == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (ChatResponse response : responses) {
            List<ContentBlock> blocks = response.getContent();
            if (blocks == null) {
                continue;
            }
            for (ContentBlock block : blocks) {
                if (block instanceof TextBlock textBlock) {
                    text.append(textBlock.getText());
                }
            }
        }
        return text.toString();
    }

    /** 创建携带角色和文本内容的记忆消息。 */
    private Msg buildMessage(MsgRole role, String text) {
        return Msg.builder().role(role).content(TextBlock.builder().text(text).build()).build();
    }

    /** 仅保留已知字段及其合法选项，防止自由输入污染偏好记忆。 */
    private Map<String, Object> normalizePreferences(Map<String, ?> preferences) {
        Map<String, PreferenceOptionVO> definitions = new LinkedHashMap<>();
        for (PreferenceCategoryVO category : buildPreferenceOptions()) {
            for (PreferenceOptionVO option : category.getItems()) {
                definitions.put(option.getKey(), option);
            }
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        if (preferences == null) {
            return normalized;
        }
        for (Map.Entry<String, ?> entry : preferences.entrySet()) {
            PreferenceOptionVO definition = definitions.get(entry.getKey());
            if (definition == null || entry.getValue() == null) {
                continue;
            }
            if ("single".equals(definition.getType())) {
                String value = String.valueOf(entry.getValue()).strip();
                if (definition.getOptions().contains(value)) {
                    normalized.put(entry.getKey(), value);
                }
                continue;
            }
            if (entry.getValue() instanceof List<?> values) {
                List<String> selected = values.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .map(String::strip)
                        .filter(definition.getOptions()::contains)
                        .distinct()
                        .toList();
                if (!selected.isEmpty()) {
                    normalized.put(entry.getKey(), selected);
                }
            }
        }
        return normalized;
    }

    /** 按界面字段标签将结构化偏好整理为长期记忆可读文本。 */
    private String formatPreferencesAsText(Map<String, Object> preferences) {
        Map<String, String> labels = getFieldLabels();
        List<String> parts = new ArrayList<>();
        preferences.forEach((key, value) -> {
            String text = value instanceof List<?> values
                    ? String.join("、", values.stream().map(String::valueOf).toList())
                    : String.valueOf(value);
            parts.add(labels.getOrDefault(key, key) + "：" + text);
        });
        return String.join("；", parts);
    }

    /** 返回界面字段到中文标签的映射。 */
    private Map<String, String> getFieldLabels() {
        return Map.ofEntries(
                Map.entry("flight_cabin", "机票舱位偏好"), Map.entry("flight_airline", "偏好航司"),
                Map.entry("flight_seat", "飞机座位偏好"), Map.entry("flight_time", "航班时间偏好"),
                Map.entry("flight_direct", "中转偏好"), Map.entry("hotel_star", "酒店星级偏好"),
                Map.entry("hotel_brand", "偏好酒店品牌"), Map.entry("hotel_room", "房型偏好"),
                Map.entry("hotel_floor", "楼层偏好"), Map.entry("hotel_location", "酒店位置偏好"),
                Map.entry("hotel_facilities", "酒店设施需求"), Map.entry("train_seat", "火车座位偏好"),
                Map.entry("train_time", "火车出发时段偏好"), Map.entry("train_position", "火车座位位置偏好"),
                Map.entry("transport", "市内交通偏好"), Map.entry("reimburse_priority", "费用敏感度"),
                Map.entry("schedule_priority", "时间安排偏好"), Map.entry("meal", "餐饮偏好"));
    }

    /** 构造前端使用的机票、酒店、火车和通用出行偏好选项。 */
    private List<PreferenceCategoryVO> buildPreferenceOptions() {
        return List.of(
                category("flight", "机票偏好",
                        option("flight_cabin", "舱位偏好", "single", "经济舱", "超级经济舱", "公务舱", "头等舱"),
                        option("flight_airline", "偏好航司", "multi", "国航(CA)", "东航(MU)", "南航(CZ)", "海航(HU)", "厦航(MF)", "深航(ZH)", "川航(3U)", "春秋(9C)", "吉祥(HO)", "山航(SC)"),
                        option("flight_seat", "座位位置", "single", "靠窗", "靠过道", "前排", "紧急出口排", "无偏好"),
                        option("flight_time", "航班时间", "single", "早班(6:00-9:00)", "上午(9:00-12:00)", "下午(12:00-18:00)", "晚班(18:00-21:00)", "红眼航班也可以", "无偏好"),
                        option("flight_direct", "中转偏好", "single", "只选直飞", "可接受一次中转", "价格优先不限中转", "无偏好")),
                category("hotel", "酒店偏好",
                        option("hotel_star", "星级偏好", "single", "经济型", "舒适型(三星)", "高档型(四星)", "豪华型(五星)", "无偏好"),
                        option("hotel_brand", "偏好品牌", "multi", "全季", "亚朵", "如家商旅", "汉庭", "维也纳", "桔子", "希尔顿", "万豪", "洲际", "凯悦", "香格里拉", "华住"),
                        option("hotel_room", "房型偏好", "single", "大床房", "双床房", "无偏好"),
                        option("hotel_floor", "楼层偏好", "single", "高楼层", "低楼层(方便出行)", "无偏好"),
                        option("hotel_location", "位置偏好", "multi", "靠近办公/会议地点", "靠近地铁/交通枢纽", "靠近市中心", "安静环境", "有停车场"),
                        option("hotel_facilities", "设施需求", "multi", "健身房", "早餐", "免费Wi-Fi", "商务中心", "洗衣服务", "接机服务")),
                category("train", "高铁/火车偏好",
                        option("train_seat", "座位等级", "single", "二等座", "一等座", "商务座", "无偏好"),
                        option("train_time", "出发时段", "single", "早班(6:00-9:00)", "上午(9:00-12:00)", "下午(12:00-18:00)", "晚班(18:00-21:00)", "无偏好"),
                        option("train_position", "座位位置", "single", "靠窗", "靠过道", "无偏好")),
                category("general", "出行习惯",
                        option("transport", "市内交通", "single", "地铁/公交优先", "打车优先", "自驾/租车", "无偏好"),
                        option("reimburse_priority", "费用敏感度", "single", "价格优先(尽量省钱)", "性价比优先(合理范围选舒适)", "体验优先(预算内最舒适)", "无偏好"),
                        option("schedule_priority", "时间安排", "single", "尽量当天往返", "提前一晚到达", "会议/办事结束当天返回", "灵活安排", "无偏好"),
                        option("meal", "餐饮偏好", "multi", "无特殊要求", "清淡饮食", "素食", "清真", "无辣", "不含海鲜", "无麸质")));
    }

    /** 创建一个分类对象，保持选项顺序与界面展示顺序一致。 */
    private PreferenceCategoryVO category(String code, String label, PreferenceOptionVO... options) {
        PreferenceCategoryVO category = new PreferenceCategoryVO();
        category.setCategory(code);
        category.setLabel(label);
        category.setItems(List.of(options));
        return category;
    }

    /** 创建一个带有合法选项的偏好字段定义。 */
    private PreferenceOptionVO option(String key, String label, String type, String... values) {
        PreferenceOptionVO option = new PreferenceOptionVO();
        option.setKey(key);
        option.setLabel(label);
        option.setType(type);
        option.setOptions(List.of(values));
        return option;
    }
}
