package cn.lwccxy.trip.agent.store.plan;

import io.agentscope.core.plan.model.Plan;
import io.agentscope.core.plan.storage.PlanStorage;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Objects;

/**
 * 基于 Redis Hash 保存 AgentScope 1.0.12 计划快照，以计划 ID 为字段名。
 * <p>共享此组件的调用方共享历史计划；本实现不承担用户或会话权限隔离。
 * 默认不设置过期时间，重启恢复能力取决于 Redis 服务的持久化配置。
 * 计划按显式调用 addPlan 时的状态保存，后续修改对象需要再次保存。
 */
public class RedisPlanStorage implements PlanStorage {

    /** 专用 Hash 键，避免扫描整个 Redis 数据库或维护额外的计划索引。 */
    public static final String HASH_KEY = "agentscope:plans:";

    private final HashOperations<String, String, String> hashOperations;

    private final String userId;

    /**
     * 创建 Redis 计划存储，复用 Spring 管理的连接与字符串序列化设置。
     *
     * @param redisTemplate Redis 字符串模板
     */
    public RedisPlanStorage(StringRedisTemplate redisTemplate, String userId) {
        this.hashOperations = Objects.requireNonNull(redisTemplate, "Redis 模板不能为空").opsForHash();
        this.userId = userId;
    }

    /**
     * 在订阅时保存完整快照，同一 ID 由 Redis HSET 原子覆盖。
     *
     * @param plan 待保存的计划
     * @return 保存完成信号；存储失败通过错误信号传播
     */
    @Override
    public Mono<Void> addPlan(Plan plan) {
        // 阻塞 Redis 调用使用有界弹性线程池，避免占用 Agent 的响应式事件线程。
        return Mono.<Void>fromRunnable(() -> {
            // =========== 前置校验 ===========
            Objects.requireNonNull(plan, "plan 不能为空");
            String planId = Objects.requireNonNull(plan.getId(), "plan.id 不能为空");
            // 使用显式快照，避免 SDK 的 JsonIgnore 标记丢失运行状态。
            hashOperations.put(HASH_KEY + userId, planId, PlanSnapshot.toJson(plan));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 在订阅时按 ID 读取计划快照，保持计划 ID 原有的大小写。
     *
     * @param planId 计划唯一标识
     * @return 命中的计划；不存在时为空 Mono
     */
    @Override
    public Mono<Plan> getPlan(String planId) {
        return Mono.fromCallable(() -> {
            Objects.requireNonNull(planId, "planId 不能为空");
            // 只将未命中转换为空结果，连接异常或非法 JSON 均向调用方传播。
            String snapshot = hashOperations.get(HASH_KEY + userId, planId);
            return snapshot == null ? null : PlanSnapshot.fromJson(snapshot);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 在订阅时读取专用 Hash 中的全部计划，返回顺序不作保证。
     *
     * @return 计划列表；没有计划时返回空列表
     */
    @Override
    public Mono<List<Plan>> getPlans() {
        // HVALS 一次读取全部快照，与 PlanStorage 返回完整列表的接口契约一致。
        return Mono.fromCallable(() -> {
                    List<String> snapshots = hashOperations.values(HASH_KEY + userId);
                    if (snapshots == null || snapshots.isEmpty()) {
                        return List.<Plan>of();
                    }
                    return snapshots.stream().map(PlanSnapshot::fromJson).toList();
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
