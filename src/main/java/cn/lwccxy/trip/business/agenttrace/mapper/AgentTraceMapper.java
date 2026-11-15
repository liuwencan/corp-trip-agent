package cn.lwccxy.trip.business.agenttrace.mapper;

import cn.lwccxy.trip.business.agenttrace.domain.entity.AgentTraceEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Agent 调用链路 Span 数据访问接口。 */
@Mapper
public interface AgentTraceMapper extends BaseMapper<AgentTraceEntity> {
}
