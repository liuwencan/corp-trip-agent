package cn.lwccxy.trip.business.agenttrace.service;

import cn.lwccxy.trip.business.agenttrace.domain.entity.AgentTraceEntity;
import cn.lwccxy.trip.business.agenttrace.mapper.AgentTraceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 提供 Agent Trace Hook 使用的 Span 持久化操作。 */
@Service
@RequiredArgsConstructor
public class AgentTracePersistenceService {

    private final AgentTraceMapper mapper;

    /** 插入 Span 开始记录，并将数据库生成的主键回填到实体。 */
    public void insert(AgentTraceEntity entity) {
        mapper.insert(entity);
    }

    /** 更新已经插入的 Span 结束状态和运行指标。 */
    public void update(AgentTraceEntity entity) {
        if (entity.getId() != null) {
            mapper.updateById(entity);
        }
    }
}
