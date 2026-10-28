package cn.lwccxy.trip.agent.context;

import java.util.Objects;

/**
 * 管理当前线程绑定的 Agent 运行时上下文。
 *
 * <p>ThreadLocal 只在当前线程内可见，在线程池任务结束后应调用 {@link #clear()}，
 * 避免复用线程携带前一次请求的用户和会话信息。</p>
 */
public final class AgentRuntimeContextHolder {

    private static final ThreadLocal<AgentRuntimeContext> CONTEXT = new ThreadLocal<>();

    /**
     * 禁止创建上下文管理器实例。
     */
    private AgentRuntimeContextHolder() {
    }

    /**
     * 将运行时上下文绑定到当前线程。
     *
     * @param context 需要绑定的 Agent 运行时上下文，不能为 null
     */
    public static void set(AgentRuntimeContext context) {
        CONTEXT.set(Objects.requireNonNull(context, "AgentRuntimeContext 不能为 null"));
    }

    /**
     * 获取当前线程绑定的运行时上下文。
     *
     * @return 当前线程的上下文；尚未绑定时返回 null
     */
    public static AgentRuntimeContext get() {
        return CONTEXT.get();
    }

    /**
     * 清除当前线程绑定的运行时上下文。
     *
     * <p>在线程池任务或请求处理结束时调用，防止线程复用导致上下文泄漏。</p>
     */
    public static void clear() {
        CONTEXT.remove();
    }
}
