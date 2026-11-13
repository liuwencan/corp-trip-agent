package cn.lwccxy.trip.agent;

import io.agentscope.core.agent.Agent;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentRegistry {

    // 记录对应会话活跃的Agent
    private static ConcurrentHashMap<String, Agent> agents = new ConcurrentHashMap<>();


}
