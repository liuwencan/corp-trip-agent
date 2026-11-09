package cn.lwccxy.trip.agent.session;

import io.agentscope.core.state.State;

/** 行程审核结果在 AgentScope Session 中的持久化状态。 */
public record ReviewResultState(String reviewResultJson) implements State {
}
