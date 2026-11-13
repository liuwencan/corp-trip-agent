package cn.lwccxy.trip.agent.memory;

import cn.lwccxy.trip.common.utils.RedisCacheUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.bailian.BailianLongTermMemory;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;

/**
 * 智能差旅助手长期记忆, 引入Redis做记忆缓存
 */
public class CropTripLongTermMemory implements LongTermMemory {


    private static final Logger logger = LoggerFactory.getLogger(CropTripLongTermMemory.class);
    private final BailianLongTermMemory bailianLongTermMemory;
    private final RedisCacheUtil redisCache;
    private final String userId;
    private static final String PREFIX_KEY = "crop:trip:preference:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    /**
     * 创建按用户隔离缓存的长期记忆适配器。
     *
     * @param bailianLongTermMemory 已绑定同一用户和记忆库的百炼记忆实例
     * @param redisCache Redis 缓存工具
     * @param userId 当前用户标识，必须与百炼记忆实例一致
     */
    public CropTripLongTermMemory(BailianLongTermMemory bailianLongTermMemory, RedisCacheUtil redisCache, String userId) {
        this.bailianLongTermMemory = bailianLongTermMemory;
        this.redisCache = redisCache;
        this.userId = userId;
    }

    /**
     * 长期记忆存储
     *
     * @param msgs 消息列表
     * @return 记录及缓存失效完成信号
     */
    @Override
    public Mono<Void> record(List<Msg> msgs) {
        // 空消息不触发远端写入，也不清除已有偏好缓存。
        if (msgs == null || msgs.isEmpty()) {
            return Mono.empty();
        }

        return bailianLongTermMemory.record(msgs)
                .doOnSuccess(record -> {
                    // 记录成功, 代表偏好更新, 主动使缓存失效
                    String key = PREFIX_KEY + userId;
                    redisCache.delete(key);
                    logger.info("[CropTripLongTermMemory] 用户ID：{}, RedisKey: {}, 偏好缓存成功清除", userId, key);
                }).doOnError(throwable -> {
                    // 只记录异常类型和条目数，避免将个人记忆正文写入日志。
                    logger.error("[CropTripLongTermMemory] 偏好记录失败，条目数：{}，异常类型：{}",
                            msgs.size(), throwable.getClass().getSimpleName());
                });
    }

    /**
     * 优先读取与当前查询匹配的缓存，未命中时检索百炼并缓存非空结果。
     *
     * @param msg 检索消息；为空或无有效文本时不发起检索
     * @return 检索结果；无有效消息、无匹配记忆或远端检索失败时返回空字符串
     */
    @Override
    public Mono<String> retrieve(Msg msg) {
        if (msg == null) {
            return Mono.just("");
        }
        logger.info("[TravelPreferenceLTM] 准备检索用户 {} 的旅行偏好记忆", userId);
        // 先查 Redis 缓存（阻塞调用放到 boundedElastic 线程），命中则不调百炼；
        // 读写必须使用同一个带前缀的 key，否则写入后永远查不到，缓存形同虚设。
        String key = PREFIX_KEY + userId;
        return Mono.fromCallable(() -> {
                    String cached = redisCache.get(key);
                    return cached != null ? cached : "";
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(cached -> {
                    if (!cached.isEmpty()) {
                        logger.info("[TravelPreferenceLTM] 用户 {} 命中偏好缓存，跳过百炼检索", userId);
                        return Mono.just(cached);
                    }
                    return bailianLongTermMemory.retrieve(msg)
                            .doOnNext(result -> {
                                // 召回成功, 存储到Redis中, 缓存30分钟
                                redisCache.put(key, result, Duration.ofMinutes(30));
                                logger.debug("[CropTripLongTermMemory] 用户 {} 检索到偏好记忆，长度={}",
                                        userId, result != null ? result.length() : 0);
                            })
                            .doOnError(e -> logger.warn("[CropTripLongTermMemory] 检索用户 {} 的旅行偏好记忆失败: {}",
                                    userId, e.getMessage()))
                            .onErrorReturn("");
                });
    }

}
