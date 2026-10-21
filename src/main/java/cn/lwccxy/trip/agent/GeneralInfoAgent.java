package cn.lwccxy.trip.agent;

import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/** 负责查询天气和目的地公共信息的子智能体。 */
@Component
public class GeneralInfoAgent {
    private final DataSource dataSource;
    private final Model model;
    private ReActAgent agent;

    /** 创建通用信息子智能体工厂。 */
    public GeneralInfoAgent(DataSource dataSource, Model model) {
        this.dataSource = dataSource;
        this.model = model;
    }

    /** 获取通用信息子智能体，并在首次调用时完成初始化。 */
    public synchronized ReActAgent getAgent() {
        if (agent == null) {
            agent = ReActAgent.builder()
                .name("GeneralInfoAgent")
                .description("负责查询天气、目的地资讯、当地交通和餐饮等通用信息")
                .model(model)
                .sysPrompt("你是企业差旅通用信息子智能体。负责查询天气、目的地资讯、当地交通、餐饮和其他出行相关信息。回答时说明信息的时间范围和不确定性，只能使用已注册的查询工具，不得编造实时数据，不负责创建订单或修改差旅单。")
                .stateStore(new MysqlAgentStateStore(dataSource, true))
                .build();
        }
        return agent;
    }

    /** 应用关闭时释放通用信息子智能体。 */
    @PreDestroy
    public synchronized void close() {
        if (agent != null) {
            agent.close();
            agent = null;
        }
    }
}
