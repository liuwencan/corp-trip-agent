package cn.lwccxy.trip.agent;

import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/** 负责理解和处理企业差旅单相关请求的子智能体。 */
@Component
public class TripRequestAgent {
    private final DataSource dataSource;
    private final Model model;
    private ReActAgent agent;

    /** 创建差旅单子智能体工厂。 */
    public TripRequestAgent(DataSource dataSource, Model model) {
        this.dataSource = dataSource;
        this.model = model;
    }

    /** 获取差旅单管理子智能体，并在首次调用时完成初始化。 */
    public synchronized ReActAgent getAgent() {
        if (agent == null) {
            agent = ReActAgent.builder()
                .name("TripRequestAgent")
                .description("负责企业差旅单的创建、查询、更新、提交审批和审批状态查询")
                .model(model)
                .sysPrompt("你是企业差旅单管理子智能体。负责理解差旅单创建、查询、更新、提交审批、撤回和审批状态查询请求。你只能通过已注册的业务工具执行数据操作，不得虚构审批结果，不得替用户批准申请。缺少必要信息时先明确询问用户。")
                .stateStore(new MysqlAgentStateStore(dataSource, true))
                .build();
        }
        return agent;
    }

    /** 应用关闭时释放差旅单管理子智能体。 */
    @PreDestroy
    public synchronized void close() {
        if (agent != null) {
            agent.close();
            agent = null;
        }
    }
}
