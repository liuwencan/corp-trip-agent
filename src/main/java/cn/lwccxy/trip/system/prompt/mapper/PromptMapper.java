package cn.lwccxy.trip.system.prompt.mapper;

import cn.lwccxy.trip.system.prompt.domain.entity.PromptEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PromptMapper extends BaseMapper<PromptEntity> {

    @Select("SELECT * FROM t_prompt WHERE id = #{id} FOR UPDATE")
    PromptEntity selectByIdForUpdate(@Param("id") Long id);
}
