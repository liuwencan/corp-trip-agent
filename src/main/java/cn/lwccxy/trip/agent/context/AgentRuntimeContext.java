package cn.lwccxy.trip.agent.context;

/**
 * 存储Agent单次调用, 会话级生命周期的上下文
 */
public class AgentRuntimeContext {

    // 用户ID
    private volatile String userId;
    // 会话ID
    private volatile String sessionId;

    public AgentRuntimeContext(String userId, String sessionId){
        this.userId = userId;
        this.sessionId = sessionId;
    }

    // ================= 建造者模式 ==================
    public static Builder build(){
        return new Builder();
    }

    public String getUserId() {
        return userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public static class Builder{

        private String userId;
        private String sessionId;

        public Builder userId(String userId){
            this.userId = userId;
            return this;
        }

        public Builder sessionId(String sessionId){
            this.sessionId = sessionId;
            return this;
        }

        public AgentRuntimeContext builder(){
            AgentRuntimeContext agentRuntimeContext = new AgentRuntimeContext(userId, sessionId);
            return agentRuntimeContext;
        }
    }

}
