package cn.lwccxy.trip.agent;

import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/** 负责综合差旅条件生成候选行程方案的子智能体。 */
@Component
public class ItineraryPlanningAgent {
    private final DataSource dataSource;
    private final Model model;
    private ReActAgent agent;

    /** 创建行程规划子智能体工厂。 */
    public ItineraryPlanningAgent(DataSource dataSource, Model model) {
        this.dataSource = dataSource;
        this.model = model;
    }

    /** 获取行程规划子智能体，并在首次调用时完成初始化。 */
    public synchronized ReActAgent getAgent() {
        if (agent == null) {
            agent = ReActAgent.builder()
                .name("ItineraryPlanningAgent")
                .description("负责规划路线、交通、住宿、饮食并进行价格和约束条件比较")
                .model(model)
                .sysPrompt("你是企业差旅行程规划子智能体。根据出差时间、目的地、预算、公司差标和用户偏好，综合规划路线、出行方式、入住方式、饮食安排、天气和目的地资讯，并比较价格、耗时、退改条件和合规性。输出多个可比较的候选方案，明确假设和数据来源。你只负责规划，不替用户做最终决策，不直接预订或修改订单。")
                .stateStore(new MysqlAgentStateStore(dataSource, true))
                .build();
        }
        return agent;
    }

    /** 应用关闭时释放行程规划子智能体。 */
    @PreDestroy
    public synchronized void close() {
        if (agent != null) {
            agent.close();
            agent = null;
        }
    }
}
