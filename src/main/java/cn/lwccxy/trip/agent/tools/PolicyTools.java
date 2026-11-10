package cn.lwccxy.trip.agent.tools;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.business.travelpolicy.domain.PolicyCheckResult;
import cn.lwccxy.trip.business.travelpolicy.domain.TravelPolicy;
import cn.lwccxy.trip.business.travelpolicy.service.TravelPolicyService;
import com.alibaba.fastjson2.JSON;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 差旅政策工具集：查询政策标准与合规校验，共享 TravelPolicyService。 */
@Component
public class PolicyTools {

    private static final Logger logger = LoggerFactory.getLogger(PolicyTools.class);

    @Autowired
    private TravelPolicyService travelPolicyService;

    /**
     * 查询指定用户和目的城市的差旅政策标准。
     *
     * @param sessionCtx 当前 Agent 会话上下文
     * @param city 目的城市
     * @return 政策 JSON
     */
    @Tool(name = "query_travel_policy",
            description = "查询指定用户和目的城市的差旅政策标准（餐标、酒店标准、交通补贴等）")
    public String queryTravelPolicy(
            AgentRuntimeContext sessionCtx,
            @ToolParam(name = "city", description = "目的城市") String city) {
        return resolvePolicyJson(sessionCtx, city, "query_travel_policy");
    }

    /**
     * 校验行程或订单是否符合当前用户可享有的差旅政策标准。
     *
     * @param sessionCtx 当前 Agent 会话上下文
     * @param city 目的城市
     * @param orderSummary 订单摘要 JSON
     * @return 合规结果 JSON
     */
    @Tool(name = "check_travel_policy",
            description = "校验行程或订单是否符合差旅政策，返回合规结果和超标说明")
    public String checkTravelPolicy(
            AgentRuntimeContext sessionCtx,
            @ToolParam(name = "city", description = "目的城市") String city,
            @ToolParam(name = "order_summary",
                    description = "订单摘要JSON，例如 {\"type\":\"HOTEL\",\"amount\":450}") String orderSummary) {

        logger.info("[TOOL][check_travel_policy] city={}, orderSummary={}", city, orderSummary);
        TravelPolicy policy = JSON.parseObject(
                resolvePolicyJson(sessionCtx, city, "check_travel_policy"), TravelPolicy.class);
        PolicyCheckResult result = travelPolicyService.checkCompliance(orderSummary, policy);
        String json = JSON.toJSONString(result);
        logger.info("[TOOL][check_travel_policy] done");
        return json;
    }

    /**
     * 获取指定城市的差旅政策 JSON：优先读取会话级缓存，未命中时查询服务并回填缓存。
     *
     * @param sessionCtx 当前 Agent 会话上下文
     * @param city 目的城市
     * @param toolName 当前工具名称
     * @return 政策 JSON
     */
    private String resolvePolicyJson(AgentRuntimeContext sessionCtx, String city, String toolName) {
        // 优先命中按城市隔离的会话缓存，避免查询和校验工具在同一会话重复查库。
        String cached = sessionCtx.getTravelPolicy(city);
        if (cached != null) {
            logger.info("[TOOL][{}] hit cache, city={}", toolName, city);
            return cached;
        }
        String userId = sessionCtx.getUserId();
        logger.info("[TOOL][{}] miss cache, userId={}, city={}", toolName, userId, city);
        TravelPolicy policy = travelPolicyService.getPolicy(userId, city);
        String result = JSON.toJSONString(policy);
        logger.info("[TOOL][{}] result={}", toolName, result);
        sessionCtx.setTravelPolicy(city, result);
        return result;
    }
}
