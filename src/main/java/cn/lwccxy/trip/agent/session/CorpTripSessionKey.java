package cn.lwccxy.trip.agent.session;

import io.agentscope.core.state.SessionKey;

/**
 * 自定义SessionKey, 因为需要做智能体上下文隔离, 所以要用 会话Id + 智能体名称做单次会话智能体上下文隔离, 否则多智能体的上下文还是在同一个Agent中
 *
 * @param sessionId 会话ID
 * @param agentName 智能体名称
 */
public record CorpTripSessionKey(String sessionId, String agentName) implements SessionKey {

    @Override
    public String toIdentifier() {
        return this.sessionId + "_" + this.agentName;
    }

    /**
     * Factory method to create a SimpleSessionKey.
     *
     * @param sessionId the session identifier
     * @return a new SimpleSessionKey instance
     */
    public static CorpTripSessionKey of(String sessionId, String agentName) {
        return new CorpTripSessionKey(sessionId, agentName);
    }
}
