package cn.lwccxy.trip.agent.tools.store;

import com.alibaba.fastjson2.JSONObject;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 按用户隔离保存途牛搜索候选方案的 Redis Hash 存储层。 */
@Component
public class TuniuSearchCandidateStore {

    private static final String KEY_PREFIX = "tuniu:search:candidates:";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    /**
     * 创建途牛搜索候选方案存储层。
     *
     * @param redisTemplate Redis 字符串模板
     */
    public TuniuSearchCandidateStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 生成用户专属 Hash Key，避免用户之间读取到彼此的候选方案。
     *
     * @param userId 用户标识
     * @return Redis Key
     */
    public static String keyOf(String userId) {
        String normalized = userId == null || userId.isBlank() ? "unknown" : userId.trim();
        String encodedUserId = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(normalized.getBytes(StandardCharsets.UTF_8));
        return KEY_PREFIX + encodedUserId;
    }

    /**
     * 写入一个查询条件对应的候选方案，并刷新用户候选 Hash 的过期时间。
     *
     * @param userId 用户标识
     * @param field 查询条件对应的 Hash field
     * @param entry 候选方案条目
     */
    public void put(String userId, String field, JSONObject entry) {
        String key = keyOf(userId);
        redisTemplate.opsForHash().put(key, field, entry.toJSONString());
        redisTemplate.expire(key, TTL);
    }

    /**
     * 读取一个查询条件对应的候选方案。
     *
     * @param userId 用户标识
     * @param field 查询条件对应的 Hash field
     * @return JSON 字符串；不存在时返回 null
     */
    public String get(String userId, String field) {
        Object value = redisTemplate.opsForHash().get(keyOf(userId), field);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 按 Hash field 批量读取候选方案，供后续工具直接透传给业务层。
     *
     * @param userId 用户标识
     * @param fields 查询 field 集合
     * @return 已命中的 field 与 JSON 值
     */
    public Map<String, String> multiGet(String userId, Collection<String> fields) {
        List<String> requested = List.copyOf(fields);
        List<Object> hashFields = new ArrayList<>(requested);
        List<Object> values = redisTemplate.opsForHash().multiGet(keyOf(userId), hashFields);
        Map<String, String> result = new LinkedHashMap<>();
        if (values == null) {
            return result;
        }
        for (int index = 0; index < requested.size() && index < values.size(); index++) {
            Object value = values.get(index);
            if (value != null) {
                result.put(requested.get(index), String.valueOf(value));
            }
        }
        return result;
    }

    /**
     * 按一次出差行程读取去程、返程交通和目的地酒店候选，供工具直接透传。
     *
     * @param userId 用户标识
     * @param origin 出发城市
     * @param destination 目的地城市
     * @param departureDate 去程日期
     * @param returnDate 返程日期
     * @return 已命中的候选方案 JSON
     */
    public Map<String, String> loadByTrip(String userId, String origin, String destination,
                                          String departureDate, String returnDate) {
        String normalizedOrigin = Objects.requireNonNullElse(origin, "unknown");
        String normalizedDestination = Objects.requireNonNullElse(destination, "unknown");
        String normalizedDeparture = Objects.requireNonNullElse(departureDate, "unknown");
        String normalizedReturn = Objects.requireNonNullElse(returnDate, "unknown");
        return multiGet(userId, List.of(
                routeField("flight", normalizedOrigin, normalizedDestination, normalizedDeparture),
                routeField("train", normalizedOrigin, normalizedDestination, normalizedDeparture),
                routeField("flight", normalizedDestination, normalizedOrigin, normalizedReturn),
                routeField("train", normalizedDestination, normalizedOrigin, normalizedReturn),
                hotelField(normalizedDestination, normalizedDeparture, normalizedReturn)));
    }

    /**
     * 清空一个用户的全部途牛搜索候选。
     *
     * @param userId 用户标识
     */
    public void clear(String userId) {
        redisTemplate.delete(keyOf(userId));
    }

    /**
     * 计算交通搜索 Hash field。
     *
     * @param type 搜索类型
     * @param departureCity 出发城市
     * @param arrivalCity 到达城市
     * @param departureDate 出发日期
     * @return Hash field
     */
    public static String routeField(String type, String departureCity, String arrivalCity, String departureDate) {
        return type + "_" + safeFieldPart(departureCity) + "_" + safeFieldPart(arrivalCity)
                + "_" + safeFieldPart(departureDate);
    }

    /**
     * 计算酒店搜索 Hash field。
     *
     * @param city 城市
     * @param checkIn 入住日期
     * @param checkOut 离店日期
     * @return Hash field
     */
    public static String hotelField(String city, String checkIn, String checkOut) {
        return "hotel_" + safeFieldPart(city) + "_" + safeFieldPart(checkIn)
                + "_" + safeFieldPart(checkOut);
    }

    /** 清理 field 片段，避免命令参数中的特殊字符改变 Hash field 结构。 */
    private static String safeFieldPart(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fff.-]", "_");
    }
}
