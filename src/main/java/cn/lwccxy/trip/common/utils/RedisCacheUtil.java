package cn.lwccxy.trip.common.utils;

import com.alibaba.fastjson2.JSON;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * 基于字符串模板提供通用 Redis 缓存操作。
 */
@Component
public class RedisCacheUtil {

    private final StringRedisTemplate redisTemplate;

    /**
     * 创建 Redis 缓存工具。
     *
     * @param redisTemplate Spring Redis 字符串模板
     */
    public RedisCacheUtil(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 保存字符串缓存。
     *
     * @param key 缓存键
     * @param value 缓存值
     */
    public void set(String key, String value) {
        // 未指定过期时间时，沿用 Redis 的持久化 Key 语义。
        redisTemplate.opsForValue().set(key, value);
    }

    /**
     * 保存带过期时间的字符串缓存。
     *
     * @param key 缓存键
     * @param value 缓存值
     * @param timeout 过期时长
     */
    public void put(String key, String value, Duration timeout) {
        // 将过期策略随写入一并提交，避免 Key 短暂成为永久缓存。
        redisTemplate.opsForValue().set(key, value, timeout);
    }

    /**
     * 仅当缓存键不存在时写入字符串，适用于简单的占位或防重复提交场景。
     *
     * @param key 缓存键
     * @param value 缓存值
     * @param timeout 过期时长
     * @return 写入成功返回 true，键已存在时返回 false
     */
    public boolean setIfAbsent(String key, String value, Duration timeout) {
        // 使用 Redis 原子条件写入，避免先判断再写入产生并发竞争。
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, value, timeout));
    }

    /**
     * 读取字符串缓存。
     *
     * @param key 缓存键
     * @return 缓存值；键不存在时返回 null
     */
    public String get(String key) {
        // StringRedisTemplate 返回原始字符串，调用方可自行决定后续解析方式。
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * 将对象序列化为 JSON 后保存。
     *
     * @param key 缓存键
     * @param value 待缓存对象
     */
    public void setObject(String key, Object value) {
        // 项目统一使用 Fastjson2 编码对象，Redis 中仍保存可读字符串。
        redisTemplate.opsForValue().set(key, JSON.toJSONString(value));
    }

    /**
     * 将对象序列化为 JSON 后保存，并设置过期时间。
     *
     * @param key 缓存键
     * @param value 待缓存对象
     * @param timeout 过期时长
     */
    public void setObject(String key, Object value, Duration timeout) {
        // 对象缓存复用字符串存储，避免引入额外 Redis 序列化格式。
        redisTemplate.opsForValue().set(key, JSON.toJSONString(value), timeout);
    }

    /**
     * 读取 JSON 缓存并转换为指定类型。
     *
     * @param key 缓存键
     * @param valueType 目标类型
     * @param <T> 目标类型
     * @return 转换后的对象；键不存在时返回 null
     */
    public <T> T getObject(String key, Class<T> valueType) {
        String value = get(key);
        // 缓存未命中时不调用 JSON 解析器，保持与字符串读取一致的 null 语义。
        return value == null ? null : JSON.parseObject(value, valueType);
    }

    /**
     * 判断缓存键是否存在。
     *
     * @param key 缓存键
     * @return 存在时返回 true
     */
    public boolean hasKey(String key) {
        // 将 Redis 的可空布尔结果归一为普通布尔值。
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    /**
     * 删除一个或多个缓存键。
     *
     * @param keys 待删除的缓存键
     * @return 实际删除的键数量；没有键被删除时返回 0
     */
    public long delete(Collection<String> keys) {
        // Spring Data Redis 在空集合时可能返回 null，统一按零个删除处理。
        Long deletedCount = redisTemplate.delete(keys);
        return deletedCount == null ? 0L : deletedCount;
    }

    /**
     * 删除一个缓存键。
     *
     * @param key 待删除的缓存键
     * @return 键存在并删除时返回 true
     */
    public boolean delete(String key) {
        // 直接使用 Redis 删除命令，避免额外的存在性查询。
        return Boolean.TRUE.equals(redisTemplate.delete(key));
    }

    /**
     * 更新缓存键的过期时长。
     *
     * @param key 缓存键
     * @param timeout 过期时长
     * @return 键存在且过期策略设置成功时返回 true
     */
    public boolean expire(String key, Duration timeout) {
        // 保留 Redis 对不存在键的原生处理结果。
        return Boolean.TRUE.equals(redisTemplate.expire(key, timeout));
    }

    /**
     * 获取缓存键的剩余过期时间。
     *
     * @param key 缓存键
     * @param timeUnit 返回时间单位
     * @return 剩余时间；永久键或不存在的键按 Redis 约定返回负值
     */
    public long getExpire(String key, TimeUnit timeUnit) {
        // 保留 Redis 对永久键和不存在键的负值语义。
        Long expiration = redisTemplate.getExpire(key, timeUnit);
        return expiration == null ? -2L : expiration;
    }

    /**
     * 写入 Hash 字段。
     *
     * @param key Hash 键
     * @param field 字段名
     * @param value 字段值
     */
    public void hashPut(String key, String field, String value) {
        // Hash 字段和值均使用字符串，复杂对象可先通过 JSON 序列化。
        redisTemplate.opsForHash().put(key, field, value);
    }

    /**
     * 读取 Hash 字段。
     *
     * @param key Hash 键
     * @param field 字段名
     * @return 字段值；字段不存在时返回 null
     */
    public String hashGet(String key, String field) {
        Object value = redisTemplate.opsForHash().get(key, field);
        // HashOperations 的值类型为 Object，统一转换为字符串返回。
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 删除一个或多个 Hash 字段。
     *
     * @param key Hash 键
     * @param fields 待删除的字段名
     * @return 实际删除的字段数量
     */
    public long hashDelete(String key, String... fields) {
        // 将 Redis 返回的可空数量归一为零。
        Long deletedCount = redisTemplate.opsForHash().delete(key, (Object[]) fields);
        return deletedCount == null ? 0L : deletedCount;
    }
}
