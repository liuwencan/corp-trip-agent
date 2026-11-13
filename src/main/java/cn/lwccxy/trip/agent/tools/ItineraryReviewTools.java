package cn.lwccxy.trip.agent.tools;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.agent.session.ReviewResultSessionStore;
import cn.lwccxy.trip.agent.tools.store.ItineraryPlanStore;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** 对已生成的往返交通与酒店方案执行可复现的多维审核。 */
@Component
public class ItineraryReviewTools {

    private static final Logger logger = LoggerFactory.getLogger(ItineraryReviewTools.class);
    private final ItineraryPlanStore itineraryPlanStore;
    private final ReviewResultSessionStore reviewResultStore;
    private final Model reviewModel;
    private final String experiencePrompt;
    private final String resiliencePrompt;
    private final String preferencePrompt;
    private final String arbitrationPrompt;

    /**
     * 创建行程方案审核工具并加载三类主观评审与仲裁提示词。
     *
     * @param itineraryPlanStore 行程规划结果存储
     * @param reviewModel 用于独立主观评审及最终仲裁的模型
     */
    public ItineraryReviewTools(ItineraryPlanStore itineraryPlanStore,
                                ReviewResultSessionStore reviewResultStore,
                                @Qualifier("strongModel") Model reviewModel) {
        this.itineraryPlanStore = itineraryPlanStore;
        this.reviewResultStore = reviewResultStore;
        this.reviewModel = reviewModel;
        this.experiencePrompt = loadPrompt("review/trip-experience-assessor.md");
        this.resiliencePrompt = loadPrompt("review/resilience-assessor.md");
        this.preferencePrompt = loadPrompt("review/preference-assessor.md");
        this.arbitrationPrompt = loadPrompt("review/review-arbitrator.md");
    }

    /**
     * 读取当前用户已保存的代表方案，逐一校验日期、路线、预算、政策舱位和住宿信息。
     *
     * @param origin 出发城市
     * @param destination 目的地城市
     * @param departureDate 去程日期
     * @param returnDate 返程日期
     * @param policy 差旅政策 JSON，可选
     * @param weatherSummary 行程天气摘要，可选
     * @param newsSummary 目的地资讯摘要，可选
     * @param userPreferences 用户差旅偏好摘要，可选
     * @param runtimeContext 当前会话上下文
     * @return 各方案的审核结论 JSON
     */
    @Tool(name = "review_itinerary_plan", description = "在 design_itinerary_plan 后调用，对当前用户保存的代表方案执行六维代码客观审核，并分别并行调用三个 LLM 评审器审核差旅体验、行程韧性和偏好匹配，最后由仲裁模型汇总并生成整改建议。传入与规划一致的城市和日期，并透传 policy、weather_summary、news_summary、user_preferences。")
    public String reviewItineraryPlan(
            @ToolParam(name = "origin", description = "出发城市，例如上海") String origin,
            @ToolParam(name = "destination", description = "目的地城市，例如杭州") String destination,
            @ToolParam(name = "departure_date", description = "去程日期，格式 YYYY-MM-DD") String departureDate,
            @ToolParam(name = "return_date", description = "返程日期，格式 YYYY-MM-DD") String returnDate,
            @ToolParam(name = "policy", description = "可选差旅政策 JSON，透传规划工具使用的 hotelLimit、hotelStarLimit、flightClass、trainSeatClass、approvalThreshold", required = false) String policy,
            @ToolParam(name = "weather_summary", description = "可选的去程和返程天气摘要，用于行程韧性评审", required = false) String weatherSummary,
            @ToolParam(name = "news_summary", description = "可选的目的地活动、交通管制和突发资讯摘要，用于行程韧性评审", required = false) String newsSummary,
            @ToolParam(name = "user_preferences", description = "可选的用户差旅偏好摘要，用于偏好匹配评审", required = false) String userPreferences,
            AgentRuntimeContext runtimeContext) {
        if (isBlank(origin) || isBlank(destination) || isBlank(departureDate) || isBlank(returnDate)) {
            return errorResult("出发城市、目的地城市、去程日期和返程日期均为必填项。");
        }
        if (runtimeContext == null || isBlank(runtimeContext.getUserId())) {
            return errorResult("缺少当前用户上下文，无法读取行程方案。");
        }

        try {
            // 读取本会话上一轮报告，交给仲裁器保持整改建议方向连续。
            String previousReview = reviewResultStore.load(runtimeContext.getSessionId());
            // =========== 从Redis中加载回规划出来的四个代表性方案 =========================
            String stored = itineraryPlanStore.load(runtimeContext.getUserId(), origin, destination, departureDate);
            if (isBlank(stored)) {
                return errorResult("未找到该行程的规划结果，请先成功调用 design_itinerary_plan。");
            }

            JSONObject plan = JSON.parseObject(stored);
            JSONArray proposals = plan.getJSONArray("proposals");
            if (proposals == null || proposals.isEmpty()) {
                return errorResult("规划结果中没有可审核的 proposals。");
            }

            // ============== 解析用户享有的差旅政策（不是通用的政策, 是与用户绑定的） =================
            JSONObject policyJson = isBlank(policy) ? new JSONObject() : JSON.parseObject(policy);
            if (policyJson == null) {
                return errorResult("policy 必须是 JSON 对象。");
            }

            // ============== request 是用户出差申请的一些参数（包含出发时间、返程时间、出差城市、出发城市）
            JSONObject request = plan.getJSONObject("user_request");

            JSONArray reviews = new JSONArray();
            int bestIndex = -1;
            double bestScore = Double.NEGATIVE_INFINITY; // 负无穷
            int bestVerdictRank = -1;
            int failCount = 0;
            int warningCount = 0;
            JSONArray objectiveReviews = new JSONArray();

            // 每个代表方案独立审核，确保单个方案的失败不会掩盖其他可用选择。
            for (int index = 0; index < proposals.size(); index++) {
                // ========= 获取每一个代表方案 =========
                JSONObject proposal = proposals.getJSONObject(index);
                // ========= 对每一个代表方案进行 6个维度上的客观校验 ==========
                JSONObject review = reviewProposal(proposal, request, origin, destination,
                        departureDate, returnDate, policyJson, index);

                reviews.add(review);
                objectiveReviews.add(review);
                // 获取方案审核结果
                String verdict = review.getString("verdict");
                if ("fail".equals(verdict)) {
                    // 审核不通过
                    failCount++;
                } else if ("warning".equals(verdict)) {
                    // 警告事项
                    warningCount++;
                }

                double score = proposal.getJSONObject("scores") == null
                        ? 0 : proposal.getJSONObject("scores").getDoubleValue("overall");
                int verdictRank = verdictRank(verdict);
                if (verdictRank > bestVerdictRank || verdictRank == bestVerdictRank && score > bestScore) {
                    // 从四个代表方案中选出分数最好的那个
                    bestVerdictRank = verdictRank;
                    bestScore = score;
                    bestIndex = index;
                }
            }

            // ======= 利用LLM并行进行三维客观审核 =======
            // 1. 差旅体验审核
            // 2. 行程韧性审核
            // 3. 偏好匹配审核
            // ======= 三个评审器各自使用独立提示词和模型请求，通过独立 Reactor 任务并行运行。 =======
            JSONObject subjectiveResults = runSubjectiveReviews(plan, objectiveReviews,
                    weatherSummary, newsSummary, userPreferences);
            JSONObject arbitration = arbitrate(objectiveReviews, subjectiveResults, bestIndex, previousReview);
            boolean hardVetoed = objectiveReviews.stream()
                    .map(JSONObject.class::cast)
                    .flatMap(review -> review.getJSONArray("checks").stream())
                    .map(JSONObject.class::cast)
                    .anyMatch(check -> "fail".equals(check.getString("status"))
                            && List.of("行程完整性", "时间合理性", "出发地与目的地", "路径合理性")
                            .contains(check.getString("dimension")));


            // 客观硬约束由代码确定，仲裁模型只能补充建议，不能覆盖否决结论。
            String finalVerdict = hardVetoed ? "fail" : arbitration.getString("verdict");
            if (isBlank(finalVerdict) || !List.of("pass", "warning", "fail").contains(finalVerdict)) {
                finalVerdict = "warning";
            }
            arbitration.put("verdict", finalVerdict);
            if (hardVetoed) {
                JSONObject arbitrationDetails = arbitration.getJSONObject("details");
                if (arbitrationDetails == null) {
                    arbitrationDetails = new JSONObject();
                    arbitration.put("details", arbitrationDetails);
                }
                arbitrationDetails.put("next_action", "fix_and_replan");
                arbitrationDetails.put("continue_remediation", true);
            }
            JSONArray finalIssues = arbitration.getJSONArray("issues") == null
                    ? new JSONArray() : arbitration.getJSONArray("issues");
            if (hardVetoed) {
                // 硬约束问题放在整改列表前部，确保仲裁输出保留代码否决原因。
                for (JSONObject objectiveReview : objectiveReviews.toJavaList(JSONObject.class)) {
                    JSONArray checks = objectiveReview.getJSONArray("checks");
                    for (int index = 0; index < checks.size(); index++) {
                        JSONObject check = checks.getJSONObject(index);
                        if ("fail".equals(check.getString("status"))) {
                            String issue = "[硬约束] " + objectiveReview.getString("proposal_id")
                                    + " " + check.getString("dimension") + "：" + check.getString("message");
                            if (!finalIssues.contains(issue)) finalIssues.add(0, issue);
                        }
                    }
                }
            }

            JSONObject result = new JSONObject();
            result.put("overall_status", finalVerdict);
            result.put("proposal_count", reviews.size());
            result.put("pass_count", reviews.size() - failCount - warningCount);
            result.put("warning_count", warningCount);
            result.put("fail_count", failCount);
            result.put("best_proposal_id", bestIndex < 0 ? null : "P" + (bestIndex + 1));
            result.put("objective_reviews", reviews);
            result.put("subjective_reviews", subjectiveResults);
            result.put("arbitration", arbitration);
            result.put("issues", finalIssues);
            result.put("suggestions", arbitration.getJSONArray("suggestions"));
            result.put("hard_vetoed", hardVetoed);
            String reportJson = result.toJSONString();
            // 审核报告写入 agentscope_session，供下一轮审核和恢复会话时读取。
            reviewResultStore.save(runtimeContext.getSessionId(), reportJson);
            logger.info("[TOOL][review_itinerary_plan] 审核完成: userId={}, proposals={}, fail={}, warning={}, verdict={}",
                    runtimeContext.getUserId(), reviews.size(), failCount, warningCount, finalVerdict);
            return result.toJSONString();
        } catch (Exception exception) {
            logger.error("[TOOL][review_itinerary_plan] 行程审核失败: {}", exception.getMessage());
            return errorResult("行程方案审核失败，请检查规划结果和政策 JSON 格式。");
        }
    }

    /**
     * 将规划方案转换为审核视图并汇总各维度检查结果。
     *
     * @param proposal 原始代表方案
     * @param request 规划时的行程请求
     * @param origin 请求出发城市
     * @param destination 请求目的地城市
     * @param departureDate 请求去程日期
     * @param returnDate 请求返程日期
     * @param policy 差旅政策
     * @param index 方案序号
     * @return 单个方案审核报告
     */
    private JSONObject reviewProposal(JSONObject proposal, JSONObject request, String origin, String destination,
                                       String departureDate, String returnDate, JSONObject policy, int index) {
        List<JSONObject> checks = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        // 获取出去程(outbound)、返程(return)、住宿(hotel)、一些指标(metrics)（总通行时长、总价格、出差停留时长）
        JSONObject outbound = proposal.getJSONObject("outbound");
        JSONObject inbound = proposal.getJSONObject("return");
        JSONObject hotel = proposal.getJSONObject("hotel");
        JSONObject metrics = proposal.getJSONObject("metrics");

        // 检查行程完整性
        boolean complete = outbound != null && inbound != null && hotel != null;
        addCheck(checks, issues, "行程完整性", complete ? "pass" : "fail",
                complete ? "去程、返程交通和住宿均已安排" : "方案缺少去程、返程交通或住宿信息");


        if (!complete) {
            suggestions.add("补齐缺失的交通或住宿候选后重新生成方案");
        }

        // =========== 1. 日期校验（去程日期不能晚于返回日期） ===========
        boolean datesValid = dateRangeValid(departureDate, returnDate);
        datesValid = datesValid && request != null
                && departureDate.equals(request.getString("departure_date"))
                && returnDate.equals(request.getString("return_date"));

        // =========== 2. 路线校验 ===================
        boolean routeValid = request != null
                && cityMatches(request.getString("origin"), origin)
                && cityMatches(request.getString("destination"), destination)
                && transportMatches(outbound, origin, destination)
                && transportMatches(inbound, destination, origin);

        // =========== 3. 城市校验 ==================
        boolean citiesValid = request != null
                && cityMatches(request.getString("origin"), origin)
                && cityMatches(request.getString("destination"), destination);
        addCheck(checks, issues, "出发地与目的地", citiesValid ? "pass" : "fail",
                citiesValid ? "规划请求中的出发地和目的地与本次审核条件一致" : "规划请求中的出发地或目的地与本次审核条件不一致");

        // =========== 4. 价格校验（判断当前组合的总花费价格是否超过该用户享有的免审批特权金额） ==========
        Double totalPrice = metrics == null ? null : metrics.getDouble("total_price");
        Double approvalLimit = policy.getDouble("approvalThreshold");
        boolean withinBudget = totalPrice != null && (approvalLimit == null || totalPrice <= approvalLimit);
        String budgetMessage = totalPrice == null ? "方案缺少总价，无法核对预算"
                : approvalLimit != null && totalPrice > approvalLimit
                ? String.format("预计总价 %.2f 超出审批阈值 %.2f", totalPrice, approvalLimit)
                : "预计总价符合已提供的审批阈值";

        // ============ 5. 酒店每晚价格的校验（校验酒店每晚的价格是否超过了用户享有的差旅政策金额） ========
        Double hotelLimit = policy.getDouble("hotelLimit");
        Double nightlyPrice = hotel == null ? null : hotel.getDouble("price_per_night");
        boolean hotelOverLimit = hotelLimit != null && nightlyPrice != null && nightlyPrice > hotelLimit;
        if (hotelOverLimit) {
            budgetMessage += String.format("；酒店单晚价格 %.2f 超过限额 %.2f", nightlyPrice, hotelLimit);
        }
        addCheck(checks, issues, "预算合规", withinBudget && !hotelOverLimit ? "pass" : "warning", budgetMessage);
        if (!withinBudget || hotelOverLimit) {
            suggestions.add("确认预算或审批额度；超出政策阈值时补充审批说明");
        }

        // ============ 6. 高铁/航班 舱位校验 以及 酒店星级上限校验 =============
        List<String> transportIssues = checkTransportPolicy(outbound, inbound, hotel, policy);
        String transportStatus = policy.isEmpty() ? "warning" : transportIssues.isEmpty() ? "pass" : "warning";
        addCheck(checks, issues, "交通与住宿政策", transportStatus,
                policy.isEmpty() ? "未提供差旅政策，无法确认舱位和酒店等级符合企业标准"
                        : transportIssues.isEmpty() ? "交通舱位和酒店星级符合已提供的差旅政策"
                        : String.join("；", transportIssues));
        if (policy.isEmpty() || !transportIssues.isEmpty()) {
            suggestions.add("补充差旅政策或调整超出标准的舱位、酒店等级");
        }

        List<String> timingWarnings = new ArrayList<>();
        if (metrics == null || metrics.getInteger("total_transit_minutes") == null) {
            timingWarnings.add("缺少完整的往返通勤时长数据");
        }
        if (outbound != null && isRedEye(outbound) || inbound != null && isRedEye(inbound)) {
            timingWarnings.add("行程包含夜间或凌晨航班");
        }
        String timingStatus = !datesValid ? "fail" : timingWarnings.isEmpty() ? "pass" : "warning";
        String timingMessage = !datesValid ? "返程日期必须晚于去程日期，且与规划时的日期一致"
                : timingWarnings.isEmpty() ? "日期顺序有效，有完整的往返通勤时长且无红眼航班"
                : String.join("；", timingWarnings);
        addCheck(checks, issues, "时间合理性", timingStatus, timingMessage);
        suggestions.addAll(timingWarnings);

        boolean routeRisk = !routeValid || hotel != null && hotel.getDouble("distance_to_dest_km") != null
                && hotel.getDoubleValue("distance_to_dest_km") > 15;
        addCheck(checks, issues, "路径合理性", routeValid ? routeRisk ? "warning" : "pass" : "fail",
                !routeValid ? "往返交通路线与本次出发地、目的地不一致"
                        : routeRisk ? "酒店距目的地较远，建议核实通勤时间" : "交通路线和酒店位置未发现明显路径风险");
        if (routeRisk) {
            suggestions.add(routeValid ? "核实酒店到目的地的实际通勤时间，必要时选择更近的酒店"
                    : "更换与本次出发地和目的地一致的交通候选");
        }

        String verdict = checks.stream().anyMatch(check -> "fail".equals(check.getString("status"))) ? "fail"
                : checks.stream().anyMatch(check -> "warning".equals(check.getString("status"))) ? "warning" : "pass";
        JSONObject result = new JSONObject();
        result.put("source", "objective");
        result.put("proposal_id", "P" + (index + 1));
        JSONArray tags = proposal.getJSONArray("tags");
        result.put("label", tags == null ? "方案" + (index + 1) : String.join("/", tags.toJavaList(String.class)));
        result.put("verdict", verdict);
        result.put("checks", checks);
        result.put("issues", issues);
        result.put("suggestions", suggestions);
        return result;
    }

    /**
     * 并行运行差旅体验、行程韧性和偏好匹配三个独立模型评审器。
     *
     * @param plan 完整规划结果
     * @param objectiveReviews 六维客观审核结果
     * @param weatherSummary 天气摘要
     * @param newsSummary 目的地资讯摘要
     * @param userPreferences 用户偏好摘要
     * @return 三个维度各自的结构化审核结果
     */
    private JSONObject runSubjectiveReviews(JSONObject plan, JSONArray objectiveReviews,
                                            String weatherSummary, String newsSummary,
                                            String userPreferences) {
        JSONObject context = new JSONObject();
        context.put("plan", plan);
        context.put("objective_reviews", objectiveReviews);
        context.put("weather_summary", weatherSummary);
        context.put("news_summary", newsSummary);
        context.put("user_preferences", userPreferences);
        List<Mono<JSONObject>> calls = List.of(
                subjectiveCall("experience", "差旅体验审核", experiencePrompt, context),
                subjectiveCall("resilience", "行程韧性审核", resiliencePrompt, context),
                subjectiveCall("preference", "偏好匹配审核", preferencePrompt, context));
        List<JSONObject> results = Mono.zip(calls, values -> {
            List<JSONObject> collected = new ArrayList<>();
            for (Object value : values) {
                collected.add((JSONObject) value);
            }
            return collected;
        }).block();
        JSONObject result = new JSONObject();
        if (results != null) {
            for (JSONObject item : results) {
                result.put(item.getString("source"), item);
            }
        }
        return result;
    }

    /**
     * 在独立线程调用一个主观审核维度并将输出规范化为统一结构。
     *
     * @param source 维度标识
     * @param label 维度中文名称
     * @param systemPrompt 该维度专用提示词
     * @param context 审核上下文
     * @return 维度审核结果；模型调用失败时返回 warning
     */
    private Mono<JSONObject> subjectiveCall(String source, String label, String systemPrompt, JSONObject context) {
        return Mono.fromCallable(() -> {
            try {
                String response = callModel(systemPrompt, context.toJSONString());
                JSONObject result = parseModelResult(response);
                result.put("source", source);
                normalizeReviewResult(result, label);
                return result;
            } catch (Exception exception) {
                logger.warn("[TOOL][review_itinerary_plan] {}评审失败: {}", source, exception.getMessage());
                JSONObject fallback = new JSONObject();
                fallback.put("source", source);
                fallback.put("verdict", "warning");
                fallback.put("issues", new JSONArray().fluentAdd(label + "模型评审失败，需人工复核"));
                fallback.put("suggestions", new JSONArray().fluentAdd("人工复核" + label + "维度"));
                return fallback;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 使用仲裁模型合并客观和主观审核结果。
     *
     * @param objectiveReviews 六维客观审核结果
     * @param subjectiveReviews 三维并行主观审核结果
     * @param bestIndex 客观评分最高的方案下标
     * @return 仲裁结论和整改单
     */
    private JSONObject arbitrate(JSONArray objectiveReviews, JSONObject subjectiveReviews, int bestIndex,
                                 String previousReview) {
        JSONObject input = new JSONObject();
        input.put("objective_reviews", objectiveReviews);
        input.put("subjective_reviews", subjectiveReviews);
        input.put("best_proposal_id", bestIndex < 0 ? null : "P" + (bestIndex + 1));
        input.put("previous_review", previousReview);
        try {
            JSONObject result = parseModelResult(callModel(arbitrationPrompt, input.toJSONString()));
            normalizeReviewResult(result, "仲裁");
            return result;
        } catch (Exception exception) {
            logger.warn("[TOOL][review_itinerary_plan] 仲裁模型失败，使用规则降级: {}", exception.getMessage());
            JSONObject fallback = new JSONObject();
            List<JSONObject> all = new ArrayList<>();
            for (JSONObject review : objectiveReviews.toJavaList(JSONObject.class)) {
                all.add(review);
            }
            all.addAll(subjectiveReviews.values().stream().map(JSONObject.class::cast).toList());
            JSONArray issues = new JSONArray();
            JSONArray suggestions = new JSONArray();
            String verdict = "pass";
            for (JSONObject item : all) {
                String itemVerdict = item.getString("verdict");
                if ("fail".equals(itemVerdict)) verdict = "fail";
                else if ("warning".equals(itemVerdict) && !"fail".equals(verdict)) verdict = "warning";
                appendUnique(issues, item.getJSONArray("issues"));
                appendUnique(suggestions, item.getJSONArray("suggestions"));
            }
            fallback.put("verdict", verdict);
            fallback.put("issues", issues);
            fallback.put("suggestions", suggestions);
            JSONObject details = new JSONObject();
            details.put("recommended_proposal_id", bestIndex < 0 ? null : "P" + (bestIndex + 1));
            details.put("next_action", "fail".equals(verdict) ? "fix_and_replan"
                    : "warning".equals(verdict) ? "manual_review" : "confirm_proceed");
            JSONArray remediation = new JSONArray();
            int priority = 1;
            for (JSONObject item : all) {
                JSONArray itemIssues = item.getJSONArray("issues");
                JSONArray itemSuggestions = item.getJSONArray("suggestions");
                if (itemIssues == null) continue;
                for (int index = 0; index < itemIssues.size(); index++) {
                    JSONObject action = new JSONObject();
                    action.put("priority", priority++);
                    action.put("issue", itemIssues.getString(index));
                    action.put("action", itemSuggestions != null && index < itemSuggestions.size()
                            ? itemSuggestions.getString(index) : "人工复核该问题并决定后续处理");
                    boolean hardConstraint = "objective".equals(item.getString("source"))
                            && "fail".equals(item.getString("verdict"));
                    action.put("hard_constraint", hardConstraint);
                    action.put("fixable_by_replanning", hardConstraint);
                    action.put("affected_proposals", new JSONArray());
                    remediation.add(action);
                }
            }
            details.put("remediation_priority", remediation);
            details.put("continue_remediation", "fail".equals(verdict));
            fallback.put("details", details);
            return fallback;
        }
    }

    /**
     * 调用 AgentScope 模型并提取回复中的文本内容。
     *
     * @param systemPrompt 本次调用的系统提示词
     * @param userPrompt 本次调用的审核输入
     * @return 模型回复文本
     */
    private String callModel(String systemPrompt, String userPrompt) {
        List<Msg> messages = List.of(
                Msg.builder().role(MsgRole.SYSTEM).name("system")
                        .content(TextBlock.builder().text(systemPrompt).build()).build(),
                Msg.builder().role(MsgRole.USER).name("user")
                        .content(TextBlock.builder().text(userPrompt).build()).build());
        List<ChatResponse> responses = reviewModel.stream(messages, null, null).collectList().block();
        StringBuilder text = new StringBuilder();
        if (responses != null) {
            for (ChatResponse response : responses) {
                List<ContentBlock> blocks = response.getContent();
                if (blocks == null) continue;
                for (ContentBlock block : blocks) {
                    if (block instanceof TextBlock textBlock) text.append(textBlock.getText());
                }
            }
        }
        if (text.isEmpty()) throw new IllegalStateException("模型未返回文本");
        return text.toString();
    }

    /**
     * 解析模型返回 JSON，并为缺失字段设置可仲裁的默认值。
     *
     * @param response 模型原始输出
     * @return 结构化模型结果
     */
    private JSONObject parseModelResult(String response) {
        String normalized = response.trim().replaceAll("(?s)^```(?:json)?\\s*|\\s*```$", "");
        return JSON.parseObject(normalized);
    }

    /**
     * 将审核结果的判定和列表字段规范化。
     *
     * @param result 模型审核结果
     * @param label 结果缺少来源时使用的维度名称
     */
    private void normalizeReviewResult(JSONObject result, String label) {
        String verdict = result.getString("verdict");
        if (!List.of("pass", "warning", "fail").contains(verdict)) {
            result.put("verdict", "warning");
        }
        if (result.getJSONArray("issues") == null) result.put("issues", new JSONArray());
        if (result.getJSONArray("suggestions") == null) result.put("suggestions", new JSONArray());
        result.putIfAbsent("source", label);
    }

    /**
     * 将不同审核维度的问题或建议去重追加到仲裁列表。
     *
     * @param target 仲裁输出列表
     * @param source 待合并的维度列表
     */
    private void appendUnique(JSONArray target, JSONArray source) {
        if (source == null) return;
        for (int index = 0; index < source.size(); index++) {
            String item = source.getString(index);
            if (item != null && !target.contains(item)) target.add(item);
        }
    }

    /**
     * 从 classpath 加载一个审核提示词。
     *
     * @param resourcePath 提示词资源相对路径
     * @return UTF-8 提示词内容
     */
    private String loadPrompt(String resourcePath) {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        try (var input = resource.getInputStream()) {
            String prompt = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (prompt.isEmpty()) throw new IllegalStateException("审核提示词不能为空: " + resourcePath);
            return prompt;
        } catch (IOException exception) {
            throw new IllegalStateException("无法加载审核提示词: " + resourcePath, exception);
        }
    }

    /**
     * 在报告中追加一项检查，并按状态收集问题。
     *
     * @param checks 检查结果列表
     * @param issues 问题列表
     * @param dimension 检查维度
     * @param status 检查状态
     * @param message 检查说明
     */
    private void addCheck(List<JSONObject> checks, List<String> issues, String dimension, String status, String message) {
        JSONObject check = new JSONObject();
        check.put("dimension", dimension);
        check.put("status", status);
        check.put("message", message);
        checks.add(check);
        if (!"pass".equals(status)) {
            issues.add(message);
        }
    }

    /**
     * 按给定方向核对交通候选中的起点和终点。
     *
     * @param transport 交通候选
     * @param expectedOrigin 预期出发城市
     * @param expectedDestination 预期到达城市
     * @return 路线匹配时返回 true
     */
    private boolean transportMatches(JSONObject transport, String expectedOrigin, String expectedDestination) {
        return transport != null && cityMatches(transport.getString("origin"), expectedOrigin)
                && cityMatches(transport.getString("destination"), expectedDestination);
    }

    /**
     * 比较城市名并容忍“市”等行政区后缀差异。
     *
     * @param actual 实际城市
     * @param expected 预期城市
     * @return 城市名称匹配时返回 true
     */
    private boolean cityMatches(String actual, String expected) {
        if (isBlank(actual) || isBlank(expected)) {
            return false;
        }
        String left = actual.replace("市", "").trim();
        String right = expected.replace("市", "").trim();
        return left.equals(right) || left.startsWith(right) || right.startsWith(left);
    }

    /**
     * 判断日期区间是否合法。
     *
     * @param departureDate 去程日期
     * @param returnDate 返程日期
     * @return 返程晚于去程且日期格式有效时返回 true
     */
    private boolean dateRangeValid(String departureDate, String returnDate) {
        try {
            return LocalDate.parse(returnDate).isAfter(LocalDate.parse(departureDate));
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    /**
     * 将审核结论转换为方案排序优先级。
     *
     * @param verdict 审核结论
     * @return pass 高于 warning，warning 高于 fail
     */
    private int verdictRank(String verdict) {
        return switch (verdict) {
            case "pass" -> 2;
            case "warning" -> 1;
            default -> 0;
        };
    }

    /**
     * 检查酒店与往返交通是否违反显式提供的差旅政策。
     *
     * @param outbound 去程交通
     * @param inbound 返程交通
     * @param hotel 酒店
     * @param totalPrice 行程总价
     * @param policy 差旅政策
     * @return 政策超限说明
     */
    private List<String> checkTransportPolicy(JSONObject outbound, JSONObject inbound, JSONObject hotel,
                                              JSONObject policy) {
        List<String> violations = new ArrayList<>();
        Integer starLimit = policy.getInteger("hotelStarLimit");
        if (hotel != null) {
            Integer stars = hotel.getInteger("star_rating");
            if (starLimit != null && stars != null && stars > starLimit) {
                violations.add("酒店星级 " + stars + " 高于政策上限 " + starLimit + " 星");
            }
        }
        checkCabin(outbound, "flightClass", "trainSeatClass", policy, violations);
        checkCabin(inbound, "flightClass", "trainSeatClass", policy, violations);
        return violations;
    }

    /**
     * 核对单程交通的舱位是否超过差旅政策允许等级。
     *
     * @param transport 单程交通
     * @param flightKey 航班政策字段
     * @param trainKey 铁路政策字段
     * @param policy 差旅政策
     * @param violations 政策问题列表
     */
    private void checkCabin(JSONObject transport, String flightKey, String trainKey,
                            JSONObject policy, List<String> violations) {
        if (transport == null || isBlank(transport.getString("cabin_class"))) {
            return;
        }
        String type = transport.getString("type");
        String allowed = "train".equalsIgnoreCase(type) ? policy.getString(trainKey) : policy.getString(flightKey);
        Integer actualRank = cabinRank(transport.getString("cabin_class"));
        Integer allowedRank = cabinRank(allowed);
        if (actualRank != null && allowedRank != null && actualRank > allowedRank) {
            violations.add("交通舱位「" + transport.getString("cabin_class") + "」高于政策允许等级「" + allowed + "」");
        }
    }

    /**
     * 将常见机票舱位和铁路席别映射为可比较等级。
     *
     * @param cabin 舱位或席别名称
     * @return 可比较等级；无法识别时返回 null
     */
    private Integer cabinRank(String cabin) {
        if (isBlank(cabin)) return null;
        String value = cabin.replace(" ", "").toLowerCase();
        if (value.contains("头等") || value.contains("特等")) return 4;
        if (value.contains("公务") || value.contains("商务") || value.contains("一等") || value.contains("软座")) return 3;
        if (value.contains("超级经济") || value.contains("高级经济")) return 2;
        if (value.contains("经济") || value.contains("二等") || value.contains("硬座")) return 1;
        return null;
    }

    /**
     * 判断航班是否在深夜起飞或清晨到达。
     *
     * @param transport 单程交通
     * @return 属于夜间或清晨航班时返回 true
     */
    private boolean isRedEye(JSONObject transport) {
        if (!"flight".equalsIgnoreCase(transport.getString("type"))) {
            return false;
        }
        return isNightTime(transport.getString("departure_time"), true)
                || isNightTime(transport.getString("arrival_time"), false);
    }

    /**
     * 解析交通时间并判断是否落入夜间航班时段。
     *
     * @param value 日期或时间文本
     * @param departure 是否判断起飞时间
     * @return 时间落入对应时段时返回 true
     */
    private boolean isNightTime(String value, boolean departure) {
        if (isBlank(value)) return false;
        try {
            LocalDateTime time = LocalDateTime.parse(value.length() == 16 ? value + ":00" : value);
            return departure ? time.getHour() >= 22 : time.getHour() < 6;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    /**
     * 判断字符串是否为空白。
     *
     * @param value 待检查文本
     * @return 文本为空或仅含空白字符时返回 true
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 构造工具统一错误 JSON。
     *
     * @param message 错误说明
     * @return 错误响应 JSON 字符串
     */
    private String errorResult(String message) {
        JSONObject result = new JSONObject();
        result.put("ok", false);
        result.put("error", message);
        return result.toJSONString();
    }
}
