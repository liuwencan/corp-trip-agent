package cn.lwccxy.trip.agent.hooks;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.agent.enums.SseEventEnum;
import cn.lwccxy.trip.agent.session.CorpTripSessionKey;
import cn.lwccxy.trip.agent.sse.SseEmitterSender;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.*;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

/**
 * 持久化Session Hook, 用于支持Agent的多轮对话
 */
public class PersistSessionHook implements Hook {

    private final Logger logger = LoggerFactory.getLogger(PersistSessionHook.class);
    private final Session session;

    public PersistSessionHook(Session session) {
        this.session = session;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        return Mono.deferContextual(contextView -> {

            if(event instanceof ReasoningChunkEvent){
                // 推理Chunk事件直接短路
                return Mono.just(event);
            }

            // 优先从 Reactor Context 获取，未传入时使用 Agent 创建时保存的上下文
            AgentRuntimeContext runtimeContext =
                    contextView.getOrDefault("agentRuntimeContext", null);

            String sessionId = null;
            if (runtimeContext != null) {
                sessionId = runtimeContext.getSessionId();
            }else{
                // 获取不到sessionId直接短路
                return Mono.just(event);
            }

            ReActAgent agent = (ReActAgent) event.getAgent();
            String agentName = agent.getName();
            String agentId = agent.getAgentId();

            SessionKey sessionKey = new CorpTripSessionKey(sessionId, agentName);

            if(event instanceof PreCallEvent){
                // 智能体处理前
                // 每次智能体开始处理前都需要去数据库加载上一轮的历史记忆
                boolean isLoad = agent.loadIfExists(session, sessionKey);
                logger.info("历史记忆加载成功, sessionId={}", sessionId);
            }else{
                agent.saveTo(session, sessionKey);
            }

            return Mono.just(event);
        });
    }
}
