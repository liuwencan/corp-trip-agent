package cn.lwccxy.trip.agent.tools;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.agent.tools.store.ItineraryPlanStore;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 根据途牛真实搜索候选计算、评估并排序往返行程方案。 */
@Component
public class ItineraryPlanDesignTools {

    private static final Logger logger = LoggerFactory.getLogger(ItineraryPlanDesignTools.class);
    private static final int MAX_PROPOSALS = 4;
    private static final double WEIGHT_TIME = 0.20;
    private static final double WEIGHT_PRICE = 0.10;
    private static final double WEIGHT_PREFERENCE = 0.40;
    private static final double WEIGHT_EXPERIENCE = 0.30;
    private static final List<String> BAD_WEATHER_KEYWORDS = List.of("暴雨", "大雾", "大风", "雷暴", "暴雪", "雾霾", "台风", "冰雹");
    private static final List<String> TAG_ORDER = List.of("综合最佳", "时间最短", "价格最低", "最符合偏好");

    private final ItineraryPlanReadTools itineraryPlanReadTools;
    private final ItineraryPlanStore itineraryPlanStore;

    /**
     * 创建行程方案计算工具。
     *
     * @param itineraryPlanReadTools 途牛搜索候选读取与转换工具
     */
    public ItineraryPlanDesignTools(ItineraryPlanReadTools itineraryPlanReadTools,
                                    ItineraryPlanStore itineraryPlanStore) {
        this.itineraryPlanReadTools = itineraryPlanReadTools;
        this.itineraryPlanStore = itineraryPlanStore;
    }

    /**
     * 读取当前行程候选，枚举并排序往返交通与住宿组合。
     *
     * @param origin 出发城市
     * @param destination 目的地城市
     * @param departureDate 去程日期，格式 YYYY-MM-DD
     * @param returnDate 返程日期，格式 YYYY-MM-DD
     * @param preferences 当前用户的出行偏好摘要
     * @param scores 候选偏好评分 JSON；留空或传 auto 时使用中性分，显式 JSON 中未打分的候选按 0 分处理
     * @param policy 差旅政策 JSON，可包含 hotelLimit、hotelStarLimit、flightClass、trainSeatClass、approvalThreshold
     * @param weatherSummary 本次行程天气摘要
     * @param runtimeContext 当前会话上下文
     * @return 规划摘要 JSON；完整代表方案已按用户和行程写入 Redis
     */
    @Tool(name = "design_itinerary_plan", description = "根据途牛 CLI 搜索结果计算往返交通与酒店组合，评估时间、价格、用户偏好、差旅政策和恶劣天气影响。完整代表方案会按当前用户和行程写入 Redis，工具只返回 redis_key、combo_count、proposal_count 和 tags 摘要，后续审核直接从 Redis 读取。候选只读取当前用户本次行程的真实搜索结果，不会编造缺失的班次或酒店。")
    public String designItineraryPlan(
            @ToolParam(name = "origin", description = "出发城市，例如上海") String origin,
            @ToolParam(name = "destination", description = "目的地城市，例如杭州") String destination,
            @ToolParam(name = "departure_date", description = "去程日期，格式 YYYY-MM-DD") String departureDate,
            @ToolParam(name = "return_date", description = "返程日期，格式 YYYY-MM-DD") String returnDate,
            @ToolParam(name = "preferences", description = "当前用户的出行偏好摘要，可选；无偏好时留空", required = false) String preferences,
            @ToolParam(name = "scores",
                    description = "LLM事先对每个候选打的偏好分 JSON。结构固定：" +
                            "{ transport_scores:{ \"T1\":{score:0-100, basis:[中文理由]}, ... }, " +
                            "hotel_scores:{ \"H1\":{score:0-100, basis:[中文理由]}, ... } }。" +
                            "key 必须与候选 id 完全一致，未打分的 id 按 0 分处理。" +
                            "留空或传 \"auto\" 时，工具自动为所有候选生成中性分(50)。",
                    required = false) String scores,
            @ToolParam(name = "policy", description = "差旅政策 JSON，可选，支持 hotelLimit、hotelStarLimit、flightClass、trainSeatClass、approvalThreshold", required = false) String policy,
            @ToolParam(name = "weather_summary", description = "去程和返程相关天气摘要，可选；恶劣天气下选择飞机会降低体验分", required = false) String weatherSummary,
            AgentRuntimeContext runtimeContext) {
        // ========= 参数的前置校验 ===========
        if (isBlank(origin) || isBlank(destination) || isBlank(departureDate) || isBlank(returnDate)) {
            return errorResult("出发城市、目的地城市、去程日期和返程日期均为必填项。");
        }
        if (runtimeContext == null || isBlank(runtimeContext.getUserId())) {
            return errorResult("缺少当前用户上下文，无法读取行程候选。");
        }

        try {
            // ======== 从Redis中读取用户候选行程方案 ==========
            JSONObject candidates = JSON.parseObject(itineraryPlanReadTools.getCandidates(
                    origin, destination, departureDate, returnDate, runtimeContext));
            JSONArray transports = nullSafeArray(candidates.getJSONArray("transport_options")); // 获取交通方案（航班/高铁/火车）
            JSONArray hotels = nullSafeArray(candidates.getJSONArray("hotel_options"));  // 获取住宿方案（酒店）
            // 参数校验
            if (transports.isEmpty() || hotels.isEmpty()) {
                return errorResult("当前行程候选不完整，需要至少有一条去程交通、一条返程交通和一家酒店；请先调用途牛平台提供的对应CLI去执行搜索。");
            }

            // ========== 工具的scores参数是LLM预先按照用户的偏好, 对每一个搜索到的候选进程打上一个偏好分
            // 留空或 auto 表示完全不表达偏好，统一为中性分(50分)；显式评分 JSON 中未出现的候选按 0 分处理。
            JSONObject scoreData = isBlank(scores) || "auto".equalsIgnoreCase(scores.trim())
                    ? generateNeutralScores(transports, hotels)
                    : parseOptionalObject(scores, "scores");

            // ========== 解析差旅政策 ========
            JSONObject policyData = parseOptionalObject(policy, "policy");
            JSONObject request = new JSONObject();
            request.put("origin", origin.trim());
            request.put("destination", destination.trim());
            request.put("departure_date", departureDate.trim());
            request.put("return_date", returnDate.trim());

            // =========== 切分去程返程 =================
            List<JSONObject> outbound = new ArrayList<>();
            List<JSONObject> inbound = new ArrayList<>();
            // 按明确方向、城市和出发日期识别去返程，兼容途牛结果没有 direction 字段的情况。
            for (int index = 0; index < transports.size(); index++) {
                JSONObject transport = transports.getJSONObject(index);
                if (transport == null) {
                    continue;
                }
                String direction = classifyDirection(transport, request);
                if ("outbound".equals(direction)) {
                    outbound.add(transport);
                } else {
                    inbound.add(transport);
                }
            }
            if (outbound.isEmpty() || inbound.isEmpty()) {
                return errorResult(String.format("候选交通未能组成往返（去程 %d 条，返程 %d 条），请检查去返程搜索是否都已完成。",
                        outbound.size(), inbound.size()));
            }

            // =================  核心方法 =========================
            // 根据 去程 + 返程 构建笛卡尔积, 进行去重, 再进行打分, 最终选出四套代表性的方案, 再去做审核
            List<JSONObject> combinations = buildCombinations(outbound, hotels, inbound,
                    scoreData, policyData, weatherSummary);
            if (combinations.isEmpty()) {
                return errorResult("没有可用的往返组合，请检查候选日期和交通时间。");
            }

            // ================= 归一化并排序 =====================
            normalizeAndRank(combinations);
            JSONObject result = buildResult(combinations, request, preferences);
            // 完整方案保存到 Redis，模型只接收摘要，后续审核通过同一行程 Key 读取原始方案。
            itineraryPlanStore.save(runtimeContext.getUserId(), origin, destination, departureDate,
                    result.toJSONString());
            JSONObject summary = buildSummary(result, combinations.size(), runtimeContext.getUserId());
            logger.info("[TOOL][design_itinerary_plan] userId={}, combos={}, proposals={}",
                    runtimeContext.getUserId(), combinations.size(), summary.getIntValue("proposal_count"));
            return summary.toJSONString();
        } catch (RuntimeException exception) {
            logger.error("[TOOL][design_itinerary_plan] 行程方案计算失败, userId={}", runtimeContext.getUserId(), exception);
            return errorResult("行程方案计算失败：" + exception.getMessage());
        }
    }

    /**
     * 枚举去程、酒店和返程的笛卡尔积，并计算每个组合的客观指标。
     *
     * @param outbound 去程候选
     * @param hotels 酒店候选
     * @param inbound 返程候选
     * @param scores 偏好评分映射
     * @param policy 差旅政策
     * @param weatherSummary 天气摘要
     * @return 初步评估后的组合列表
     */
    private List<JSONObject> buildCombinations(List<JSONObject> outbound, JSONArray hotels,
                                               List<JSONObject> inbound, JSONObject scores,
                                               JSONObject policy, String weatherSummary) {
        List<JSONObject> combinations = new ArrayList<>();

        // ============  获取LLM对行程方案的打分选项 ==============
        JSONObject transportScores = nullSafeObject(scores.getJSONObject("transport_scores"));
        JSONObject hotelScores = nullSafeObject(scores.getJSONObject("hotel_scores"));

        // 遍历返程
        for (JSONObject go : outbound) {
            for (int hotelIndex = 0; hotelIndex < hotels.size(); hotelIndex++) {
                JSONObject hotel = hotels.getJSONObject(hotelIndex);
                if (hotel == null) {
                    continue;
                }
                for (JSONObject back : inbound) {
                    // 构建出每一个组合： 去程 * 返程 * 住宿（根据每一套组合再去算具体的指标）
                    JSONObject combination = evaluateCombination(go, hotel, back, transportScores,
                            hotelScores, policy, weatherSummary);
                    if (combination != null) {
                        combinations.add(combination);
                    }
                }
            }
        }
        return combinations;
    }

    /**
     * 计算单个交通和住宿组合的价格、时长、偏好、政策及体验数据。
     *
     * @param go 去程交通
     * @param hotel 酒店
     * @param back 返程交通
     * @param transportScores 交通偏好评分
     * @param hotelScores 酒店偏好评分
     * @param policy 差旅政策
     * @param weatherSummary 天气摘要
     * @return 组合数据；返程早于去程到达时返回 null
     */
    private JSONObject evaluateCombination(JSONObject go, JSONObject hotel, JSONObject back,
                                            JSONObject transportScores, JSONObject hotelScores,
                                            JSONObject policy, String weatherSummary) {
        LocalDateTime arrival = parseDateTime(go.getString("arrival_time"));
        LocalDateTime returnDeparture = parseDateTime(back.getString("departure_time"));
        // 排除时间明确冲突的组合；时间缺失时保留候选并在输出中将时长标为未知。
        if (arrival != null && returnDeparture != null && !returnDeparture.isAfter(arrival)) {
            return null;
        }

        int goMinutes = durationMinutes(go);
        int backMinutes = durationMinutes(back);
        int nights = Math.max(1, hotel.getIntValue("nights", 1));
        double hotelTotal = hotel.getDoubleValue("price_per_night") * nights; // 住宿总价格 = 酒店每晚价格 * 要住的天数
        double totalPrice = go.getDoubleValue("price") + back.getDoubleValue("price") + hotelTotal; // 出差全程花费 = 去程花费 + 住宿花费 + 返程花费
        double preferenceScore = (resolveScore(transportScores, go.getString("id")) // 偏好分数 = (去程偏好分 + 返程偏好分 + 住宿偏好分) / 3
                + resolveScore(hotelScores, hotel.getString("id"))
                + resolveScore(transportScores, back.getString("id"))) / 3.0;

        // 收集费用标志
        JSONArray flags = collectExperienceFlags(go, back, goMinutes, backMinutes, weatherSummary);

        JSONArray violations = new JSONArray();
        JSONArray warnings = new JSONArray();
        // 政策分
        double policyScore = evaluatePolicy(go, hotel, back, totalPrice, policy, violations, warnings);
        // 体验分
        double experienceScore = Math.max(0, 100 - experiencePenalty(flags));
        JSONObject combination = new JSONObject();
        combination.put("combo_id", go.getString("id") + "|" + hotel.getString("id") + "|" + back.getString("id"));
        combination.put("outbound", go); // 去程
        combination.put("hotel", hotel); // 住宿
        combination.put("return", back); // 返程
        combination.put("outbound_minutes", goMinutes); // 去程花费时间
        combination.put("return_minutes", backMinutes); // 返程花费时间
        combination.put("total_transit_minutes", goMinutes + backMinutes); // 总通行花费时间
        combination.put("total_price", round2(totalPrice)); // 出差全程花费（通行 + 住宿）
        combination.put("preference_score", round2(preferenceScore)); // 这个组合的偏好分
        combination.put("policy_score", policyScore); // 这个组合的政策分
        combination.put("policy_violations", violations); // 组合的（政策）违规项
        combination.put("warnings", warnings); // 组合的（政策）警告项
        combination.put("experience_score", experienceScore); // 组合的体验分
        combination.put("experience_flags", flags); // 组合的体验标记（判断字符串是否包含, 用于扣减体验分）
        combination.put("stay_hours", stayHours(arrival, returnDeparture)); // 出差停留时长（返回日期 - 出发日期）
        combination.put("preference_basis", collectPreferenceBasis(go, hotel, back, transportScores, hotelScores));
        return combination;
    }

    /**
     * 按候选集合的时间和价格范围归一化，并计算综合得分。
     *
     * @param combinations 待排序组合
     */
    private void normalizeAndRank(List<JSONObject> combinations) {
        // =========== 先从所有组合中获取 4个指标 =============
        // 1. 通行时长最短
        // 2. 通行时长最长
        // 3. 总行程花费（通行 + 住宿）最低价格
        // 4. 总行程花费（通行 + 住宿）最高价格
        double minTime = combinations.stream().mapToDouble(item -> item.getDoubleValue("total_transit_minutes")).min().orElse(0);
        double maxTime = combinations.stream().mapToDouble(item -> item.getDoubleValue("total_transit_minutes")).max().orElse(0);
        double minPrice = combinations.stream().mapToDouble(item -> item.getDoubleValue("total_price")).min().orElse(0);
        double maxPrice = combinations.stream().mapToDouble(item -> item.getDoubleValue("total_price")).max().orElse(0);

        for (JSONObject combination : combinations) {
            // ====================== 具体的归一化处理 ==========================
            // 1. 算出每个组合的 时间得分、价格得分、综合得分
            // 指标越小得分越高；同一指标完全相同时按 100 分处理，避免除零。
            double timeScore = minMaxScore(combination.getDoubleValue("total_transit_minutes"), minTime, maxTime, true);
            double priceScore = minMaxScore(combination.getDoubleValue("total_price"), minPrice, maxPrice, true);
            combination.put("time_score", timeScore); // 时间得分
            combination.put("price_score", priceScore); // 价格得分
            combination.put("overall_score", round2(timeScore * WEIGHT_TIME // 总体验分
                    + priceScore * WEIGHT_PRICE
                    + combination.getDoubleValue("preference_score") * WEIGHT_PREFERENCE
                    + combination.getDoubleValue("experience_score") * WEIGHT_EXPERIENCE));
        }
    }

    /**
     * 从全部组合中挑选四类代表方案，并按综合得分排序。
     *
     * @param combinations 已完成评分的组合
     * @param request 本次行程请求
     * @param preferences 用户偏好摘要
     * @return 结构化方案结果
     */
    private JSONObject buildResult(List<JSONObject> combinations, JSONObject request, String preferences) {
        Map<String, JSONObject> selected = new LinkedHashMap<>();
        // 从归一化后的方案组合中, 选出四个最具代表性的方案出来
        selected.put("综合最佳", best(combinations, "overall_score", false));
        selected.put("时间最短", best(combinations, "total_transit_minutes", true));
        selected.put("价格最低", best(combinations, "total_price", true));
        selected.put("最符合偏好", best(combinations, "preference_score", false));

        // 相同组合可能赢得多个代表标签，合并标签后只输出一次。
        Map<String, JSONObject> merged = new LinkedHashMap<>();
        for (String tag : TAG_ORDER) {
            JSONObject combination = selected.get(tag);
            if (combination == null) {
                continue;
            }
            String comboId = combination.getString("combo_id");
            JSONObject proposal = merged.computeIfAbsent(comboId, ignored -> toProposal(combination));
            proposal.getJSONArray("tags").add(tag);
        }

        JSONArray proposals = new JSONArray(merged.values());
        proposals.sort((left, right) -> Double.compare(
                ((JSONObject) right).getJSONObject("scores").getDoubleValue("overall"),
                ((JSONObject) left).getJSONObject("scores").getDoubleValue("overall")));

        if (proposals.size() > MAX_PROPOSALS) {
            proposals = new JSONArray(proposals.subList(0, MAX_PROPOSALS));
        }

        JSONObject meta = new JSONObject();
        meta.put("combo_count", combinations.size()); // 组合总数
        meta.put("generated_at", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))); // 四个代表组合产出的时间
        meta.put("dimension_weights", new JSONObject(Map.of("time", WEIGHT_TIME, "price", WEIGHT_PRICE,
                "preference", WEIGHT_PREFERENCE, "experience", WEIGHT_EXPERIENCE))); // 各个维度当时的权重（价格、通行时长、偏好、综合体验）
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("meta", meta);
        result.put("user_request", request); // 用户当时的出差申请参数（包含出发时间、返程时间、出发城市、返程城市信息）
        result.put("preferences", preferences == null ? "" : preferences); // 用户偏好
        result.put("proposals", proposals); //
        return result;
    }

    /**
     * 从完整规划结果提取供模型使用的摘要，避免把代表方案 JSON 透传到上下文。
     *
     * @param result 已保存到 Redis 的完整规划结果
     * @param comboCount 参与计算的组合数量
     * @param userId 当前用户标识
     * @return 规划摘要
     */
    private JSONObject buildSummary(JSONObject result, int comboCount, String userId) {
        JSONArray proposals = nullSafeArray(result.getJSONArray("proposals"));
        JSONObject tags = new JSONObject();
        for (int index = 0; index < proposals.size(); index++) {
            JSONObject proposal = proposals.getJSONObject(index);
            if (proposal == null) {
                continue;
            }
            double overall = proposal.getJSONObject("scores").getDoubleValue("overall");
            JSONArray proposalTags = nullSafeArray(proposal.getJSONArray("tags"));
            for (int tagIndex = 0; tagIndex < proposalTags.size(); tagIndex++) {
                tags.put(proposalTags.getString(tagIndex), overall);
            }
        }
        JSONObject summary = new JSONObject();
        summary.put("ok", true);
        summary.put("redis_key", ItineraryPlanStore.keyOf(userId));
        summary.put("combo_count", comboCount);
        summary.put("proposal_count", proposals.size());
        summary.put("tags", tags);
        return summary;
    }

    /**
     * 将组合转换为对外方案结构。
     *
     * @param combination 已评分组合
     * @return 包含交通、酒店、指标和风险说明的方案
     */
    private JSONObject toProposal(JSONObject combination) {
        JSONObject proposal = new JSONObject();
        proposal.put("tags", new JSONArray());
        proposal.put("outbound", copyLeg(combination.getJSONObject("outbound"), combination.getIntValue("outbound_minutes")));
        proposal.put("hotel", combination.getJSONObject("hotel"));
        proposal.put("return", copyLeg(combination.getJSONObject("return"), combination.getIntValue("return_minutes")));
        JSONObject metrics = new JSONObject();
        metrics.put("total_price", combination.get("total_price"));
        metrics.put("total_transit_minutes", combination.get("total_transit_minutes"));
        metrics.put("total_transit_duration", formatDuration(combination.getIntValue("total_transit_minutes")));
        metrics.put("stay_hours", combination.get("stay_hours"));
        proposal.put("metrics", metrics);
        JSONObject scores = new JSONObject();
        scores.put("time", combination.get("time_score"));
        scores.put("price", combination.get("price_score"));
        scores.put("preference", combination.get("preference_score"));
        scores.put("policy", combination.get("policy_score"));
        scores.put("experience", combination.get("experience_score"));
        scores.put("overall", combination.get("overall_score"));
        proposal.put("scores", scores);
        proposal.put("preference_basis", combination.get("preference_basis"));
        proposal.put("policy_violations", combination.get("policy_violations"));
        proposal.put("warnings", combination.get("warnings"));
        proposal.put("experience_flags", combination.get("experience_flags"));
        return proposal;
    }

    /**
     * 复制交通候选并补充通勤时长指标。
     *
     * @param transport 原始交通候选
     * @param minutes 通勤分钟数
     * @return 输出方案使用的交通数据
     */
    private JSONObject copyLeg(JSONObject transport, int minutes) {
        JSONObject leg = new JSONObject(transport);
        leg.put("transit_minutes", minutes);
        leg.put("transit_duration", minutes <= 0 ? null : String.format("%d小时%02d分", minutes / 60, minutes % 60));
        return leg;
    }

    /**
     * 在组合中选择指定指标的最优项。
     *
     * @param combinations 组合列表
     * @param metric 比较字段
     * @param lowerIsBetter 指标越低是否越优
     * @return 最优组合
     */
    private JSONObject best(List<JSONObject> combinations, String metric, boolean lowerIsBetter) {
        Comparator<JSONObject> comparator = Comparator.comparingDouble(item -> item.getDoubleValue(metric));
        return combinations.stream().min(lowerIsBetter ? comparator : comparator.reversed()).orElse(null);
    }

    /**
     * 识别交通候选的去返程方向。
     *
     * @param transport 交通候选
     * @param request 行程城市和日期
     * @return outbound 表示去程，return 表示返程
     */
    private String classifyDirection(JSONObject transport, JSONObject request) {
        String direction = normalize(transport.getString("direction")).toLowerCase();
        if (Set.of("outbound", "去程", "go", "depart").contains(direction)) {
            return "outbound";
        }
        if (Set.of("return", "返程", "back", "inbound").contains(direction)) {
            return "return";
        }
        String transportOrigin = normalize(transport.getString("origin"));
        if (cityMatches(transportOrigin, request.getString("origin"))) {
            return "outbound";
        }
        if (cityMatches(transportOrigin, request.getString("destination"))) {
            return "return";
        }
        LocalDateTime departure = parseDateTime(transport.getString("departure_time"));
        LocalDate returnDate = parseDate(request.getString("return_date"));
        if (departure != null && returnDate != null && !departure.toLocalDate().isBefore(returnDate)) {
            return "return";
        }
        return "outbound";
    }

    /**
     * 判断城市名是否相同或包含行政区后缀差异。
     *
     * @param actual 候选城市
     * @param expected 请求城市
     * @return 两个有效名称存在前缀匹配时返回 true
     */
    private boolean cityMatches(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        String left = actual.replaceAll("市$", "");
        String right = expected.replaceAll("市$", "");
        return left.equals(right) || left.startsWith(right) || right.startsWith(left);
    }

    /**
     * 收集政策超限和舱位不符合提示，并计算政策分。
     *
     * @param go 去程候选
     * @param hotel 酒店候选
     * @param back 返程候选
     * @param totalPrice 往返交通与住宿总价
     * @param policy 差旅政策
     * @param violations 硬性超限说明
     * @param warnings 软性限制说明
     * @return 0 到 100 的政策分
     */
    private double evaluatePolicy(JSONObject go, JSONObject hotel, JSONObject back, double totalPrice,
                                  JSONObject policy, JSONArray violations, JSONArray warnings) {
        Double hotelLimit = policy.getDouble("hotelLimit");
        // 判断这个组合的住宿价格是否超过该用户享有的酒店价格限制, 超过则给出提示
        if (hotelLimit != null && hotel.getDoubleValue("price_per_night") > hotelLimit) {
            violations.add(String.format("酒店单晚价格 %.2f 超过限额 %.2f", hotel.getDoubleValue("price_per_night"), hotelLimit));
        }
        // 判断酒店星级限制
        Integer starLimit = policy.getInteger("hotelStarLimit");
        if (starLimit != null && hotel.getIntValue("star_rating") > starLimit) {
            warnings.add(String.format("酒店星级 %d 高于政策上限 %d 星", hotel.getIntValue("star_rating"), starLimit));
        }
        // 判断审批阈值, 是否需要人去审批
        Double approvalThreshold = policy.getDouble("approvalThreshold");
        if (approvalThreshold != null && totalPrice > approvalThreshold) {
            violations.add(String.format("行程总价 %.2f 超过审批阈值 %.2f", totalPrice, approvalThreshold));
        }
        checkCabin(go, "去程", policy, warnings);
        checkCabin(back, "返程", policy, warnings);

        // violations（违反）、warnings警告
        // 偏好基础分（100）
        return Math.max(0, 100 - violations.size() * 40 - warnings.size() * 15);
    }

    /**
     * 检查交通舱位是否超出差旅政策允许范围。
     *
     * @param leg 交通候选
     * @param label 行程方向名称
     * @param policy 差旅政策
     * @param warnings 政策提示集合
     */
    private void checkCabin(JSONObject leg, String label, JSONObject policy, JSONArray warnings) {
        String cabin = normalize(leg.getString("cabin_class"));
        String policyKey = "train".equals(leg.getString("type")) ? "trainSeatClass" : "flightClass";
        String allowed = normalize(policy.getString(policyKey));
        if (cabin != null && allowed != null && !isCabinCompliant(cabin, allowed)) {
            warnings.add(label + "舱位“" + cabin + "”与政策允许等级“" + allowed + "”不一致");
        }
    }

    /**
     * 按常见舱位等级判断实际舱位是否不高于政策上限。
     *
     * @param cabin 实际舱位
     * @param allowed 政策允许的最高舱位
     * @return 实际舱位符合政策时返回 true
     */
    private boolean isCabinCompliant(String cabin, String allowed) {
        Integer cabinRank = cabinRank(cabin);
        Integer allowedRank = cabinRank(allowed);
        return cabinRank == null || allowedRank == null || cabinRank <= allowedRank;
    }

    /**
     * 将航班或火车舱位映射为从低到高的等级。
     *
     * @param cabin 舱位名称
     * @return 等级数值；无法识别时返回 null
     */
    private Integer cabinRank(String cabin) {
        String normalized = cabin.replace(" ", "").toLowerCase();
        if (normalized.contains("超级经济") || normalized.contains("高级经济")) return 2;
        if (normalized.contains("经济") || normalized.contains("二等") || normalized.contains("硬座")) return 1;
        if (normalized.contains("公务") || normalized.contains("商务") || normalized.contains("一等") || normalized.contains("软座")) return 3;
        if (normalized.contains("头等") || normalized.contains("特等")) return 4;
        return null;
    }

    /**
     * 根据时间、偏好和天气为交通与酒店返回归一化偏好分。
     *
     * @param scoreMap 候选评分映射
     * @param candidateId 候选 ID
     * @return 0 到 100 的偏好分；未提供时为中性分 50
     */
    private double resolveScore(JSONObject scoreMap, String candidateId) {
        if (candidateId == null || scoreMap == null || scoreMap.get(candidateId) == null) {
            return 0;
        }
        Object value = scoreMap.get(candidateId);
        double score = value instanceof Number number ? number.doubleValue() : scoreMap.getJSONObject(candidateId).getDoubleValue("score");
        return Math.clamp(score, 0, 100);
    }

    /**
     * 为本次检索得到的全部交通和酒店候选生成中性偏好分。
     *
     * @param transports 交通候选
     * @param hotels 酒店候选
     * @return 符合 transport_scores / hotel_scores 结构的中性评分对象
     */
    private JSONObject generateNeutralScores(JSONArray transports, JSONArray hotels) {
        JSONObject result = new JSONObject();
        JSONObject transportScores = new JSONObject();
        JSONObject hotelScores = new JSONObject();
        JSONArray basis = new JSONArray();
        basis.add("未提供候选偏好评分，使用中性分");

        // 每个真实候选都生成评分，确保 auto 模式不受显式评分缺项规则影响。
        addNeutralScores(transports, transportScores, basis);
        addNeutralScores(hotels, hotelScores, basis);
        result.put("transport_scores", transportScores);
        result.put("hotel_scores", hotelScores);
        return result;
    }

    /**
     * 为候选数组中的每个 ID 填入同一份中性评分。
     *
     * @param candidates 候选列表
     * @param target 对应的评分映射
     * @param basis 中性评分说明
     */
    private void addNeutralScores(JSONArray candidates, JSONObject target, JSONArray basis) {
        for (int index = 0; index < candidates.size(); index++) {
            JSONObject candidate = candidates.getJSONObject(index);
            if (candidate == null || isBlank(candidate.getString("id"))) {
                continue;
            }
            JSONObject score = new JSONObject();
            score.put("score", 50);
            score.put("basis", new JSONArray(basis));
            target.put(candidate.getString("id"), score);
        }
    }

    /**
     * 收集评分理由并标明对应行程部分。
     *
     * @param go 去程交通
     * @param hotel 酒店
     * @param back 返程交通
     * @param transportScores 交通评分映射
     * @param hotelScores 酒店评分映射
     * @return 偏好评分理由列表
     */
    private JSONArray collectPreferenceBasis(JSONObject go, JSONObject hotel, JSONObject back,
                                             JSONObject transportScores, JSONObject hotelScores) {
        JSONArray basis = new JSONArray();
        addBasis(basis, "去程", transportScores, go.getString("id"));
        addBasis(basis, "住宿", hotelScores, hotel.getString("id"));
        addBasis(basis, "返程", transportScores, back.getString("id"));
        return basis;
    }

    /**
     * 将单个候选的评分理由追加到结果列表。
     *
     * @param target 输出理由列表
     * @param label 行程部分名称
     * @param scoreMap 候选评分映射
     * @param candidateId 候选 ID
     */
    private void addBasis(JSONArray target, String label, JSONObject scoreMap, String candidateId) {
        JSONObject score = scoreMap == null ? null : scoreMap.getJSONObject(candidateId);
        JSONArray reasons = score == null ? null : score.getJSONArray("basis");
        if (reasons != null) {
            for (int index = 0; index < reasons.size(); index++) {
                target.add(label + "：" + reasons.getString(index));
            }
        }
    }

    /**
     * 收集红眼、过长通勤和恶劣天气选择飞机等体验风险。
     *
     * @param go 去程交通
     * @param back 返程交通
     * @param goMinutes 去程分钟数
     * @param backMinutes 返程分钟数
     * @param weatherSummary 天气摘要
     * @return 风险标记列表
     */
    private JSONArray collectExperienceFlags(JSONObject go, JSONObject back, int goMinutes,
                                             int backMinutes, String weatherSummary) {
        JSONArray flags = new JSONArray();
        collectLegFlags(go, "去程", goMinutes, flags);
        collectLegFlags(back, "返程", backMinutes, flags);

        if (hasSevereWeather(weatherSummary)
                && ("flight".equals(go.getString("type")) || "flight".equals(back.getString("type")))) {
            flags.add("恶劣天气下选择飞机出行");
        }
        return flags;
    }

    /**
     * 收集单程交通的夜间出行和长时间通勤风险。
     *
     * @param leg 单程交通
     * @param label 行程方向
     * @param minutes 单程分钟数
     * @param flags 风险标记列表
     */
    private void collectLegFlags(JSONObject leg, String label, int minutes, JSONArray flags) {
        if ("flight".equals(leg.getString("type"))) {
            LocalDateTime departure = parseDateTime(leg.getString("departure_time"));
            LocalDateTime arrival = parseDateTime(leg.getString("arrival_time"));
            // 判断当前航班是否为红眼航班（红眼航班通常是指晚上出发，隔天凌晨到达）
            if (departure != null && departure.getHour() >= 22 || arrival != null && arrival.getHour() < 6) {
                flags.add(label + "为红眼航班");
            }
            if ("去程".equals(label) && arrival != null && arrival.getHour() >= 21) {
                flags.add("去程到达时间较晚");
            }
        }
        if (minutes >= 360) {
            flags.add(label + "单程通勤超过 6 小时");
        } else if (minutes >= 240) {
            flags.add(label + "单程通勤超过 4 小时");
        }
    }

    /**
     * 根据体验风险计算体验扣分。
     *
     * @param flags 已收集的风险标记
     * @return 体验扣分
     */
    private double experiencePenalty(JSONArray flags) {
        double penalty = 0;
        for (int index = 0; index < flags.size(); index++) {
            String flag = flags.getString(index);
            if (flag.contains("红眼")) penalty += 30;
            else if (flag.contains("较晚")) penalty += 15;
            else if (flag.contains("6 小时")) penalty += 25;
            else if (flag.contains("4 小时")) penalty += 15;
            else if (flag.contains("恶劣天气")) penalty += 25;
        }
        return penalty;
    }

    /**
     * 计算交通候选的通勤时长。
     *
     * @param leg 交通候选
     * @return 通勤分钟数；时间无法解析时为 0
     */
    private int durationMinutes(JSONObject leg) {
        LocalDateTime departure = parseDateTime(leg.getString("departure_time"));
        LocalDateTime arrival = parseDateTime(leg.getString("arrival_time"));
        if (departure == null || arrival == null || arrival.isBefore(departure)) {
            return 0;
        }
        return (int) Math.round((arrival.toEpochSecond(ZoneOffset.UTC) - departure.toEpochSecond(ZoneOffset.UTC)) / 60.0);
    }

    /**
     * 计算去程到达至返程出发之间的停留小时数。
     *
     * @param arrival 去程到达时间
     * @param returnDeparture 返程出发时间
     * @return 停留小时数；时间缺失时返回 null
     */
    private Double stayHours(LocalDateTime arrival, LocalDateTime returnDeparture) {
        if (arrival == null || returnDeparture == null) {
            return null;
        }
        return round2((returnDeparture.toEpochSecond(ZoneOffset.UTC) - arrival.toEpochSecond(ZoneOffset.UTC)) / 3600.0);
    }

    /**
     * 解析常见的途牛日期时间格式。
     *
     * @param value 日期时间文本
     * @return 解析结果；文本无效时返回 null
     */
    private LocalDateTime parseDateTime(String value) {
        if (isBlank(value)) {
            return null;
        }
        String normalized = value.trim().replace("Z", "");
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))) {
            try {
                return LocalDateTime.parse(normalized, formatter);
            } catch (RuntimeException ignored) {
                // 不同途牛服务的时间字段精度不同，继续尝试其他常见格式。
            }
        }
        try {
            return LocalDate.parse(normalized).atStartOfDay();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 解析 YYYY-MM-DD 日期。
     *
     * @param value 日期文本
     * @return 日期；文本无效时返回 null
     */
    private LocalDate parseDate(String value) {
        try {
            return isBlank(value) ? null : LocalDate.parse(value.trim());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 安全解析可选 JSON 对象入参。
     *
     * @param value JSON 文本
     * @param name 参数名称，用于错误定位
     * @return JSON 对象；空文本返回空对象
     */
    private JSONObject parseOptionalObject(String value, String name) {
        if (isBlank(value)) {
            return new JSONObject();
        }
        JSONObject result = JSON.parseObject(value);
        if (result == null) {
            throw new IllegalArgumentException(name + " 必须是 JSON 对象。");
        }
        return result;
    }

    /**
     * 计算线性归一化分数。
     *
     * @param value 原始指标
     * @param min 指标最小值
     * @param max 指标最大值
     * @param lowerIsBetter 指标越低是否越优
     * @return 0 到 100 的分数
     */
    private double minMaxScore(double value, double min, double max, boolean lowerIsBetter) {
        if (Double.compare(min, max) == 0) {
            return 100;
        }
        double ratio = (value - min) / (max - min);
        if (lowerIsBetter) {
            ratio = 1 - ratio;
        }
        return round2(Math.clamp(ratio, 0, 1) * 100);
    }

    /**
     * 判断天气摘要是否包含严重天气关键词。
     *
     * @param weatherSummary 天气摘要
     * @return 包含严重天气提示时返回 true
     */
    private boolean hasSevereWeather(String weatherSummary) {
        return weatherSummary != null && BAD_WEATHER_KEYWORDS.stream().anyMatch(weatherSummary::contains);
    }

    /**
     * 构造错误 JSON。
     *
     * @param message 错误说明
     * @return 统一错误响应 JSON 字符串
     */
    private String errorResult(String message) {
        return new JSONObject(Map.of("ok", false, "error", message)).toJSONString();
    }

    /**
     * 将 JSON 数组为空值归一化为空数组。
     *
     * @param value 原数组
     * @return 非空数组
     */
    private JSONArray nullSafeArray(JSONArray value) {
        return value == null ? new JSONArray() : value;
    }

    /**
     * 将 JSON 对象为空值归一化为空对象。
     *
     * @param value 原对象
     * @return 非空对象
     */
    private JSONObject nullSafeObject(JSONObject value) {
        return value == null ? new JSONObject() : value;
    }

    /**
     * 规范化文本，空白值返回 null。
     *
     * @param value 原始文本
     * @return 去除首尾空格的文本或 null
     */
    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 判断文本是否为空白。
     *
     * @param value 待检查文本
     * @return 文本为空或仅含空白字符时返回 true
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 将金额或小时数保留两位小数。
     *
     * @param value 原始数值
     * @return 四舍五入后的数值
     */
    private double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }

    /**
     * 将分钟数格式化为小时和分钟。
     *
     * @param minutes 总分钟数
     * @return 中文时长文本
     */
    private String formatDuration(int minutes) {
        return String.format("%d小时%02d分", minutes / 60, minutes % 60);
    }
}
