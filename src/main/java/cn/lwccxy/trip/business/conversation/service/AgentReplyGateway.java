package cn.lwccxy.trip.business.conversation.service;

import io.agentscope.core.event.AgentEvent;
import reactor.core.publisher.Flux;

public interface AgentReplyGateway {
    Flux<AgentEvent> stream(Long userId, String sessionId, String question);
    String modelName();
}
