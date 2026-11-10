package cn.lwccxy.trip.business.travelpolicy.service;

import cn.lwccxy.trip.agent.service.CityTransitTimeService;
import cn.lwccxy.trip.business.travelpolicy.domain.PolicyCheckResult;
import cn.lwccxy.trip.business.travelpolicy.domain.TravelPolicy;
import cn.lwccxy.trip.business.travelpolicy.domain.entity.TravelPolicyRuleEntity;
import cn.lwccxy.trip.business.travelpolicy.mapper.TravelPolicyRuleMapper;
import cn.lwccxy.trip.business.userprofile.domain.entity.UserProfileEntity;
import cn.lwccxy.trip.business.userprofile.mapper.UserProfileMapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 根据用户档案与目的地政策规则查询政策，并检查订单合规性。 */
@Service
public class TravelPolicyService {

    private static final Logger logger = LoggerFactory.getLogger(TravelPolicyService.class);

    private final UserProfileMapper userProfileMapper;
    private final TravelPolicyRuleMapper travelPolicyRuleMapper;
    private final CityTransitTimeService cityTransitTimeService;

    /**
     * 创建差旅政策服务。
     *
     * @param userProfileMapper 用户档案访问接口
     * @param travelPolicyRuleMapper 差旅政策规则访问接口
     * @param cityTransitTimeService 城市等级配置服务
     */
    public TravelPolicyService(UserProfileMapper userProfileMapper,
                               TravelPolicyRuleMapper travelPolicyRuleMapper,
                               CityTransitTimeService cityTransitTimeService) {
        this.userProfileMapper = userProfileMapper;
        this.travelPolicyRuleMapper = travelPolicyRuleMapper;
        this.cityTransitTimeService = cityTransitTimeService;
    }

    /**
     * 按用户职级和目的城市等级获取差旅政策。
     *
     * @param userId 用户标识
     * @param destinationCity 目的城市
     * @return 用户在指定城市适用的差旅政策
     */
    public TravelPolicy getPolicy(String userId, String destinationCity) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("user_id 不能为空");
        }
        // 用户职级来自独立用户档案，规则查询必须同时满足职级区间与目的城市等级。
        UserProfileEntity profile = userProfileMapper.selectById(userId);
        if (profile == null) {
            throw new IllegalArgumentException("用户档案不存在，无法获取差旅政策：userId=" + userId);
        }
        String level = profile.getLevel();
        if (level == null || level.isBlank()) {
            throw new IllegalStateException("用户未配置职级，无法获取差旅政策：userId=" + userId);
        }

        String cityTier = cityTransitTimeService.resolvePolicyCityTier(destinationCity);
        int levelNumber = parseLevelNumber(level);
        TravelPolicyRuleEntity rule = travelPolicyRuleMapper.selectOne(
                new LambdaQueryWrapper<TravelPolicyRuleEntity>()
                        .le(TravelPolicyRuleEntity::getLevelMin, levelNumber)
                        .ge(TravelPolicyRuleEntity::getLevelMax, levelNumber)
                        .eq(TravelPolicyRuleEntity::getCityTier, cityTier)
                        .last("LIMIT 1"));
        if (rule == null) {
            throw new IllegalStateException(String.format(
                    "未找到匹配的差旅政策规则：level=%s(num=%d), cityTier=%s", level, levelNumber, cityTier));
        }

        logger.info("[TravelPolicyService] userId={}, level={}, city={}, cityTier={}",
                userId, level, destinationCity, cityTier);
        TravelPolicy policy = new TravelPolicy();
        policy.setUserLevel(level);
        policy.setDestinationCity(destinationCity);
        policy.setCityTier(cityTier);
        policy.setFlightClass(rule.getFlightClass());
        policy.setTrainSeatClass(rule.getTrainSeatClass());
        policy.setHotelLimit(valueOrZero(rule.getHotelLimit()));
        policy.setHotelStarLimit(rule.getHotelStarLimit() == null ? 0 : rule.getHotelStarLimit());
        policy.setDailyMealLimit(valueOrZero(rule.getDailyMealLimit()));
        policy.setDailyTransportLimit(valueOrZero(rule.getDailyTransportLimit()));
        policy.setApprovalThreshold(valueOrZero(rule.getApprovalThreshold()));
        policy.setAdvanceBookingDays(rule.getAdvanceBookingDays() == null ? 0 : rule.getAdvanceBookingDays());
        return policy;
    }

    /**
     * 校验订单摘要是否符合差旅政策。
     *
     * @param orderSummaryJson 订单摘要 JSON
     * @param policy 已匹配的差旅政策
     * @return 合规结果及超标说明
     */
    public PolicyCheckResult checkCompliance(String orderSummaryJson, TravelPolicy policy) {
        PolicyCheckResult result = PolicyCheckResult.compliant();
        JSONObject order = JSON.parseObject(orderSummaryJson);
        if (order == null) {
            return result;
        }

        // 按参考实现依据订单类型检查舱位或酒店金额；缺失的舱位字段不阻止其他校验。
        String type = order.getString("type");
        double amount = order.getDoubleValue("amount");
        if ("FLIGHT".equalsIgnoreCase(type)) {
            String flightClass = order.getString("flightClass");
            if (flightClass != null && !isCabinCompliant(flightClass, policy.getFlightClass(), false)) {
                result.setCompliant(false);
                result.getViolations().add("机票舱位超出政策标准：允许 " + policy.getFlightClass());
            }
        } else if ("HOTEL".equalsIgnoreCase(type)) {
            if (amount <= 0) {
                result.setCompliant(false);
                result.getViolations().add("酒店订单金额缺失或无效（amount=" + amount + "），无法校验合规性");
                result.getSuggestions().add("请在 order_summary 中提供正确的酒店金额后重新校验");
            } else if (amount > policy.getHotelLimit()) {
                result.setCompliant(false);
                result.getViolations().add("酒店金额超出政策上限：" + policy.getHotelLimit());
                result.getSuggestions().add("建议选择 " + policy.getHotelLimit() + " 元以下酒店");
            }
        } else if ("TRAIN".equalsIgnoreCase(type)) {
            String seatClass = order.getString("seatClass");
            if (seatClass != null && !isCabinCompliant(seatClass, policy.getTrainSeatClass(), true)) {
                result.setCompliant(false);
                result.getViolations().add("火车座位超出政策标准：允许 " + policy.getTrainSeatClass());
            }
        }
        return result;
    }

    /**
     * 解析职级中的数字部分，例如 P7 转换为 7。
     *
     * @param level 用户职级
     * @return 职级数字
     */
    private int parseLevelNumber(String level) {
        String digits = level.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            throw new IllegalArgumentException("职级格式无效，无法解析数字：" + level);
        }
        return Integer.parseInt(digits);
    }

    /**
     * 比较订单舱位与政策允许舱位。
     *
     * @param actual 订单舱位
     * @param allowedSpec 政策允许的最高舱位，可包含多个斜线或逗号分隔选项
     * @param train 是否为火车席别
     * @return 订单舱位符合政策时返回 true
     */
    private boolean isCabinCompliant(String actual, String allowedSpec, boolean train) {
        if (actual == null || actual.isBlank() || allowedSpec == null || allowedSpec.isBlank()) {
            return true;
        }
        int actualRank = cabinRank(actual, train);
        for (String allowed : allowedSpec.replace('，', ',').replace('/', ',').split(",")) {
            String normalizedAllowed = allowed.trim();
            int allowedRank = cabinRank(normalizedAllowed, train);
            if (actualRank >= 0 && allowedRank >= 0 && actualRank <= allowedRank) {
                return true;
            }
            String normalizedActual = actual.trim();
            if (actualRank < 0 || allowedRank < 0) {
                if (normalizedActual.equalsIgnoreCase(normalizedAllowed)
                        || normalizedAllowed.toLowerCase().contains(normalizedActual.toLowerCase())
                        || normalizedActual.toLowerCase().contains(normalizedAllowed.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 将常见机票舱位或火车席别映射为等级。
     *
     * @param cabin 舱位或席别名称
     * @param train 是否为火车席别
     * @return 等级数值；无法识别时返回 -1
     */
    private int cabinRank(String cabin, boolean train) {
        String value = cabin.trim().toLowerCase();
        if (train) {
            if (value.contains("二等") || value.equals("second")) return 1;
            if (value.contains("一等")) return 2;
            if (value.contains("商务座")) return 3;
        } else {
            if (value.contains("经济") || value.equals("economy")) return 1;
            if (value.contains("商务") || value.equals("business")) return 2;
            if (value.contains("头等") || value.equals("first")) return 3;
        }
        return -1;
    }

    /**
     * 将可能为空的政策金额转换为 0。
     *
     * @param value 政策金额
     * @return 非空金额或 0
     */
    private double valueOrZero(Double value) {
        return value == null ? 0 : value;
    }
}
