package cn.lwccxy.trip.system.prompt.mapper;

import cn.lwccxy.trip.system.prompt.domain.entity.PromptVersionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PromptVersionMapper extends BaseMapper<PromptVersionEntity> {

    @Select("SELECT COALESCE(MAX(version_no), 0) FROM t_prompt_version WHERE prompt_id = #{promptId}")
    Integer selectMaxVersionNo(@Param("promptId") Long promptId);
}
