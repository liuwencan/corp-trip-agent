package cn.lwccxy.trip.agent.listener;

import cn.lwccxy.trip.agent.AgentRegistry;
import cn.lwccxy.trip.business.chat.ChatAgentPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** 接收集群中断广播，并在持有目标会话 Agent 的节点完成中断处理。 */
@Component
public class AgentInterruptMessageListener implements MessageListener {

    private final Logger logger = LoggerFactory.getLogger(AgentInterruptMessageListener.class);
    private final AgentRegistry agentRegistry;
    private final ChatAgentPipeline chatAgentPipeline;

    /**
     * 创建跨节点 Agent 中断消息监听器。
     *
     * @param agentRegistry 当前节点的 Agent 注册表
     * @param chatAgentPipeline 聊天流水线，用于复用中断后的 SSE 收尾
     */
    public AgentInterruptMessageListener(AgentRegistry agentRegistry, ChatAgentPipeline chatAgentPipeline) {
        this.agentRegistry = agentRegistry;
        this.chatAgentPipeline = chatAgentPipeline;
    }

    /**
     * 处理 Redis Pub/Sub 中的会话中断请求。
     *
     * @param message Redis 消息，消息体为 UTF-8 编码的会话 ID
     * @param pattern 订阅模式；当前使用固定频道订阅，因此无需解析
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        // 频道中只传递会话 ID，避免序列化 AgentScope 对象或暴露节点内存状态。
        String sessionId = new String(message.getBody(), StandardCharsets.UTF_8);
        if (sessionId.isBlank()) {
            logger.warn("[AgentInterruptMessageListener] 收到空会话 ID，忽略中断广播");
            return;
        }

        // 接收端只查本机注册表，禁止再次广播，避免节点之间形成消息回环。
        if (!agentRegistry.interruptLocally(sessionId)) {
            logger.debug("[AgentInterruptMessageListener] 当前节点未持有目标会话的活跃 Agent，sessionId={}",
                    sessionId);
            return;
        }

        logger.info("[AgentInterruptMessageListener] 当前节点命中会话并完成 Agent 中断，sessionId={}", sessionId);
        // 命中节点持有执行期间的 SSE 连接，因此由该节点通知客户端并结束响应流。
        chatAgentPipeline.notifyInterrupted(sessionId);
    }
}
