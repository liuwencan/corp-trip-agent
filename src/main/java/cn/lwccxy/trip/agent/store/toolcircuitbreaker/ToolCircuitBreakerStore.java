package cn.lwccxy.trip.agent.store.toolcircuitbreaker;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 用于工具熔断处理与Redis交互
 */
@Component
public class ToolCircuitBreakerStore {

    private final StringRedisTemplate redisTemplate;

    // 需要熔断的工具名单
    private final static Set<String> NEED_CIRCUIT_BREAKERS_TOOL_NAME = Set.of("query_weather", "query_destination_news");
    // ====== Redis前缀Key ========
    // 记录每个工具的异常失败次数
    private final static String FAIL_PREFIX = "tool:failcount:";
    private final static String CIRCUIT_BREAKER_PREFIX = "tool:circuitbreaker:";
    // 作为 CIRCUIT_BREAKER_PREFIX 中的一个字段, 表示该工具是否处于熔断状态
    private final static String IS_OPEN = "isOpen";
    // 记录工具开始熔断那一时刻的时间
    private final static String START_TIME = "at";
    // 记录工具熔断的次数
    private final static String FREQUENCY = "n";

    // 过期时间
    private final static Long expiredTime = 11111L;

    public ToolCircuitBreakerStore(StringRedisTemplate stringRedisTemplate){
        this.redisTemplate = stringRedisTemplate;
    }

    // =========== Lua脚本 ===============
    /**
     * 失败计数原子脚本：INCR 自增连续失败计数后统一续期，返回自增后的值。
     * INCR 与 EXPIRE 在单次脚本内原子完成，避免两次往返之间失败留下无 TTL 的脏 key。
     *
     * <p>KEYS[1] = fail key；ARGV[1] = TTL 秒数。</p>
     */
    private static final RedisScript<Long> INCR_FAILURE_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('INCR', KEYS[1]) "
                    + "redis.call('EXPIRE', KEYS[1], ARGV[1]) "
                    + "return v",
            Long.class);

    /**
     * 进入 OPEN 状态原子脚本：HINCRBY 递增降级代数 + HSET 记录开启时间戳 + EXPIRE 续期，
     * 返回自增后的降级代数。三步在单次脚本内原子完成，避免中途失败导致
     * “代数已增但未记开启时间” 或 “无 TTL 脏 key” 等状态不一致。
     *
     * <p>KEYS[1] = gen key；ARGV[1] = 代数字段名(n)；ARGV[2] = 开启时间字段名(at)；
     * ARGV[3] = 开启时间戳(毫秒)；ARGV[4] = TTL 秒数。</p>
     */
    private static final RedisScript<Long> OPEN_NEXT_GENERATION_SCRIPT = new DefaultRedisScript<>(
            "local n = redis.call('HINCRBY', KEYS[1], ARGV[1], 1) "
                    + "redis.call('HSET', KEYS[1], ARGV[2], ARGV[3]) "
                    + "redis.call('EXPIRE', KEYS[1], ARGV[4]) "
                    + "return n",
            Long.class);


    /**
     * 判断该工具是否受控, 需要做监测熔断处理
     * @param toolName 工具名称
     * @return
     */
    public boolean isNeedCircuitBreaker(String toolName) {
        if(toolName == null) return false;
        return NEED_CIRCUIT_BREAKERS_TOOL_NAME.contains(toolName);
    }

    /**
     * 重置连续失败计数（恢复成功后调用）
     * @param toolName
     */
    public void resetFailureCount(String toolName) {
        redisTemplate.delete(FAIL_PREFIX + toolName);
    }

    /**
     * 清除工具 OPEN（熔断） 状态（含降级代数与开启时间戳）
     * @param toolName
     */
    public void clearOpen(String toolName) {
        redisTemplate.delete(CIRCUIT_BREAKER_PREFIX + toolName);
    }

    /**
     * 获取当前连续失败计数（不修改）
     * @param toolName
     * @return
     */
    public long getFailureCount(String toolName) {
        return parseLong(redisTemplate.opsForValue().get(FAIL_PREFIX + toolName));
    }

    /**
     * 对工具失败计数自增, 并设置过期时间, 用Lua脚本保证原子性
     * @param toolName
     * @return
     */
    public long incrementFailureCount(String toolName) {
        Long val = redisTemplate.execute(
                INCR_FAILURE_SCRIPT,
                Collections.singletonList(FAIL_PREFIX + toolName),
                String.valueOf(86400L)); // 一天过期时间
        return val != null ? val : 1L;
    }

    /**
     * 判断该工具是否处于熔断状态
     * @param toolName
     * @return true代表开放状态（没有处于熔断）, false代表熔断
     */
    public boolean isOpen(String toolName) {
        // HashKey
        String key = CIRCUIT_BREAKER_PREFIX + toolName;
        Boolean isOpen = redisTemplate.opsForHash().hasKey(key, START_TIME);
        return Boolean.TRUE.equals(isOpen);
    }

    /**
     * 工具进入 OPEN（熔断） 状态：原子地递增降级代数、记录当前时间为开启时间戳，
     * 返回按指数退避计算出的冷却时长（秒）。
     *
     * <p>OPEN 状态判定：{@code gen:{toolName}} Hash 中存在 {@code at} 字段即为 OPEN。</p>
     */
    public long openWithNextGeneration(String toolName) {
        // 开启时间戳仍由应用侧生成，保持与 isCooldownExpired() 中 System.currentTimeMillis() 一致的时钟基准。
        // Lua脚本返回的是自增后的降级代数n
        Long newGen = redisTemplate.execute(
                OPEN_NEXT_GENERATION_SCRIPT,
                Collections.singletonList(CIRCUIT_BREAKER_PREFIX + toolName),
                FREQUENCY,
                START_TIME,
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(86400L));
        long gen = newGen != null ? newGen : 1L;
        // 通过最新的降级代数n计算出该工具的冷却时长
        return computeCooldown(gen);
    }

    /**
     * 判断指定 tool 是否已过冷却期。
     * 不在 OPEN 状态视为已过冷却（防御性处理）。
     */
    public boolean isCooldownExpired(String toolName) {
        // 1. 获取当前工具的开始熔断时间
        Object atObj = redisTemplate.opsForHash().get(CIRCUIT_BREAKER_PREFIX + toolName, START_TIME);
        if (atObj == null) {
            return true;
        }
        long openedAt;
        try {
            // 熔断开启时间
            openedAt = Long.parseLong(atObj.toString());
        } catch (NumberFormatException e) {
            return true;
        }
        // 2. 计算出当前工具的冷却时间
        long cooldown = getCooldownSeconds(toolName);
        if (cooldown <= 0) {
            return true;
        }
        // 3. 判断工具是否已过冷却时长（cooldown返回的是秒, 转换为毫秒所以要*1000, 才能去做比较）
        return System.currentTimeMillis() >= openedAt + cooldown * 1000L;
    }

    public long getCooldownSeconds(String toolName) {
        // 1. 获取工具的熔断降级代数
        Object val = redisTemplate.opsForHash().get(CIRCUIT_BREAKER_PREFIX + toolName, FREQUENCY);
        long n = parseLong(val == null ? null : val.toString());

        if (n <= 0) {
            return 0L;
        }
        return computeCooldown(n);
    }

    /**
     * 计算冷却时间
     * @param n
     * @return
     */
    private long computeCooldown(long n) {
        // 30 * 2的0次方 / 2的1次方
        double raw = 30 * Math.pow(2, Math.max(0, n - 1));
        // 向上取整
        long cooldown = (long) Math.ceil(raw);
        // 冷却时长最长为600秒, 防止指数级无限增长导致工具熔断时长过长不合理
        return Math.min(cooldown, 600);
    }

    // ============== 辅助方法 ===================
    private static long parseLong(String val) {
        if (val == null) {
            return 0L;
        }
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

}
