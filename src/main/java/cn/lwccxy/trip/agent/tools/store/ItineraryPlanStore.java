package cn.lwccxy.trip.agent.tools.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 按用户隔离保存行程规划结果，供规划工具写入和审核流程读取。 */
@Component
public class ItineraryPlanStore {

    private static final Logger logger = LoggerFactory.getLogger(ItineraryPlanStore.class);
    private static final String KEY_PREFIX = "planner:result:";
    private static final Duration RESULT_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    /**
     * 创建行程规划结果存储层。
     *
     * @param redisTemplate Redis 字符串模板
     */
    public ItineraryPlanStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 计算当前用户的规划结果 Redis Key。
     *
     * @param userId 用户标识
     * @return 用户隔离的 Redis Key
     */
    public static String keyOf(String userId) {
        String normalized = userId == null || userId.isBlank() ? "unknown" : userId.trim();
        return KEY_PREFIX + normalized.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    /**
     * 根据行程参数生成规划结果 Hash field。
     *
     * @param origin 出发城市
     * @param destination 目的地城市
     * @param departureDate 去程日期
     * @return 行程结果 field
     */
    public static String tripKey(String origin, String destination, String departureDate) {
        return safePart(origin) + "_" + safePart(destination) + "_" + safePart(departureDate);
    }

    /**
     * 保存完整规划结果，并刷新用户结果 Hash 的过期时间。
     *
     * @param userId 用户标识
     * @param origin 出发城市
     * @param destination 目的地城市
     * @param departureDate 去程日期
     * @param resultJson 包含完整 proposals 的规划结果 JSON
     */
    public void save(String userId, String origin, String destination, String departureDate, String resultJson) {
        String key = keyOf(userId);
        String field = tripKey(origin, destination, departureDate);
        // 完整方案只落 Redis，不回传给模型，避免后续审核依赖模型透传大 JSON。
        redisTemplate.opsForHash().put(key, field, resultJson);
        redisTemplate.expire(key, RESULT_TTL);
        logger.info("[ItineraryPlanStore] 已保存规划结果: key={}, field={}", key, field);
    }

    /**
     * 读取指定行程的完整规划结果。
     *
     * @param userId 用户标识
     * @param origin 出发城市
     * @param destination 目的地城市
     * @param departureDate 去程日期
     * @return 完整规划结果 JSON；不存在时返回 null
     */
    public String load(String userId, String origin, String destination, String departureDate) {
        String key = keyOf(userId);
        String field = tripKey(origin, destination, departureDate);
        Object value = redisTemplate.opsForHash().get(key, field);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 清理指定用户的全部规划结果。
     *
     * @param userId 用户标识
     */
    public void clear(String userId) {
        redisTemplate.delete(keyOf(userId));
    }

    /**
     * 规范化 Hash field 片段，避免城市或日期中的特殊字符改变 field 结构。
     *
     * @param value 原始片段
     * @return 安全片段
     */
    private static String safePart(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fff.-]", "_");
    }
}
