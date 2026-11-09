package cn.lwccxy.trip.agent.session;

import io.agentscope.core.session.Session;
import io.agentscope.core.state.SimpleSessionKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 使用 AgentScope Session 持久化审核报告，支持跨轮审核仲裁。 */
@Component
public class ReviewResultSessionStore {

    private static final Logger logger = LoggerFactory.getLogger(ReviewResultSessionStore.class);
    private static final String REVIEW_RESULT_FIELD = "reviewResult";
    private static final String SESSION_KEY_SUFFIX = ":review_result";

    private final Session session;

    /**
     * 创建审核结果存储。
     *
     * @param session AgentScope Session 实现，当前由 MySQL Session 提供
     */
    public ReviewResultSessionStore(Session session) {
        this.session = session;
    }

    /**
     * 保存本轮审核报告。
     *
     * @param sessionId 对话会话标识
     * @param reviewResultJson 完整审核报告 JSON
     */
    public void save(String sessionId, String reviewResultJson) {
        if (isBlank(sessionId) || isBlank(reviewResultJson)) {
            return;
        }
        try {
            session.save(keyOf(sessionId), REVIEW_RESULT_FIELD, new ReviewResultState(reviewResultJson));
            logger.debug("[ReviewResultSessionStore] 已保存审核结果: sessionId={}", sessionId);
        } catch (Exception exception) {
            logger.error("[ReviewResultSessionStore] 保存审核结果失败: sessionId={}", sessionId, exception);
        }
    }

    /**
     * 读取上一轮审核报告。
     *
     * @param sessionId 对话会话标识
     * @return 上一轮审核 JSON，不存在时返回 null
     */
    public String load(String sessionId) {
        if (isBlank(sessionId)) {
            return null;
        }
        try {
            return session.get(keyOf(sessionId), REVIEW_RESULT_FIELD, ReviewResultState.class)
                    .map(ReviewResultState::reviewResultJson)
                    .orElse(null);
        } catch (Exception exception) {
            logger.error("[ReviewResultSessionStore] 读取审核结果失败: sessionId={}", sessionId, exception);
            return null;
        }
    }

    /**
     * 清除当前会话的审核报告。
     *
     * @param sessionId 对话会话标识
     */
    public void clear(String sessionId) {
        if (isBlank(sessionId)) {
            return;
        }
        try {
            session.delete(keyOf(sessionId));
        } catch (Exception exception) {
            logger.error("[ReviewResultSessionStore] 清除审核结果失败: sessionId={}", sessionId, exception);
        }
    }

    /**
     * 构造审核结果专用的 Session Key，避免与 Agent 对话状态冲突。
     *
     * @param sessionId 对话会话标识
     * @return 审核结果专用键
     */
    private SimpleSessionKey keyOf(String sessionId) {
        return SimpleSessionKey.of(sessionId.trim() + SESSION_KEY_SUFFIX);
    }

    /**
     * 判断文本是否为空白。
     *
     * @param value 待检查文本
     * @return 文本为空或只含空白时返回 true
     */
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
