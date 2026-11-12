package cn.lwccxy.trip.agent.context;

import cn.lwccxy.trip.business.trip.domain.vo.TripOrderVO;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 存储Agent单次调用, 会话级生命周期的上下文
 */
public class AgentRuntimeContext {

    // 用户ID
    private volatile String userId;
    // 会话ID
    private volatile String sessionId;
    // 链路追踪ID
    private volatile String traceId;
    // 订单信息
    private volatile TripOrderVO cacheOrder;
    // 差旅政策按目的地城市区分，在单次 Agent 会话内复用查询结果。
    private final Map<String, String> travelPolicyByCity = new ConcurrentHashMap<>();
    // 预订授权与查询状态跟随本次 Agent 调用生命周期。
    private final BookingExecutionState bookingExecutionState = new BookingExecutionState();

    public AgentRuntimeContext(String userId, String sessionId, String traceId){
        this.userId = userId;
        this.sessionId = sessionId;
        this.traceId = traceId;
    }

    // ================= 建造者模式 ==================
    public static Builder build(){
        return new Builder();
    }

    public String getUserId() {
        return userId;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public TripOrderVO getTripOrder() {
        return cacheOrder;
    }

    public void setCacheOrder(TripOrderVO cacheOrder) {
        this.cacheOrder = cacheOrder;
    }

    /** 获取本次 Agent 调用专属的预订流程状态。 */
    public BookingExecutionState getBookingExecutionState() {
        return bookingExecutionState;
    }


    /**
     * 获取指定城市的会话级差旅政策缓存。
     *
     * @param city 目的地城市
     * @return 已缓存的政策 JSON；城市为空或未命中时返回 null
     */
    public String getTravelPolicy(String city) {
        return city == null ? null : travelPolicyByCity.get(city.trim());
    }

    /**
     * 保存指定城市的会话级差旅政策缓存。
     *
     * @param city 目的地城市
     * @param travelPolicy 政策 JSON
     */
    public void setTravelPolicy(String city, String travelPolicy) {
        if (city != null && travelPolicy != null) {
            travelPolicyByCity.put(city.trim(), travelPolicy);
        }
    }

    public static class Builder{

        private String userId;
        private String sessionId;
        private String traceId;

        public Builder userId(String userId){
            this.userId = userId;
            return this;
        }

        public Builder sessionId(String sessionId){
            this.sessionId = sessionId;
            return this;
        }

        public Builder traceId(String traceId){
            this.traceId = traceId;
            return this;
        }

        public AgentRuntimeContext builder(){
            return new AgentRuntimeContext(userId, sessionId, traceId);
        }
    }

}
