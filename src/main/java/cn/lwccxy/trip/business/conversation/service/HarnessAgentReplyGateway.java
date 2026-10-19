package cn.lwccxy.trip.business.conversation.service;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class HarnessAgentReplyGateway implements AgentReplyGateway {
    private final ObjectProvider<HarnessAgent> agents;
    private final Model model;

    public HarnessAgentReplyGateway(@Qualifier("masterAgent") ObjectProvider<HarnessAgent> agents,
                                   @Qualifier("strongModel") Model model) {
        this.agents = agents;
        this.model = model;
    }

    @Override public Flux<AgentEvent> stream(Long userId, String sessionId, String question) {
        return Flux.using(agents::getObject, agent -> agent.streamEvents(new UserMessage(question),
                RuntimeContext.builder().userId(userId.toString()).sessionId(sessionId).build()), HarnessAgent::close);
    }

    @Override public String modelName() { return model.getModelName(); }
}
