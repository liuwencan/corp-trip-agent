package cn.lwccxy.trip.agent.store.activeagent;

import io.agentscope.core.state.State;

/**
 * 会话当前活跃 Agent 的持久化状态。
 *
 * <p>作为 {@link State} 实现，用于通过 AgentScope {@link io.agentscope.core.session.Session}
 * 在 MySQL/Redis 等持久化存储中保存当前会话正在使用的 Agent 名称，以支持集群环境下的
 * activeAgent 共享。</p>
 *
 * @author Hollis
 */
public record ActiveAgentState(String agentName) implements State {
}
