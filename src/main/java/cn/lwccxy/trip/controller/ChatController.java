package cn.lwccxy.trip.controller;

import cn.lwccxy.trip.common.model.ChatRequestDTO;
import cn.lwccxy.trip.common.util.SpringContextUtil;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@RequestMapping("/chat")
public class ChatController {

    @Resource
    private SpringContextUtil springContextUtil;


    // 核心聊天接口
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequestDTO chatRequestDTO){
        // TODO 用户ID从SaToken框架解析出来
        Long userId = 1L;

        // SSE连接
        SseEmitter emitter = new SseEmitter();

        String question = chatRequestDTO.getQuestion();
        String sessionId = chatRequestDTO.getSessionId();
        // 用户消息
        UserMessage userMessage = new UserMessage(question);
        // 加载 uid + sessionId 维度的历史会话上下文信息
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId(String.valueOf(userId))
                .sessionId(sessionId)
                .build();

        HarnessAgent agent = SpringContextUtil.getBean("masterAgent", HarnessAgent.class);
        agent.streamEvents(List.of(userMessage), runtimeContext);

        Flux<AgentEvent> agentEventFlux = agent.streamEvents(userMessage, runtimeContext)
                .doOnNext(event -> {

                    System.out.println(event);
                });

        return emitter;
    }


}
