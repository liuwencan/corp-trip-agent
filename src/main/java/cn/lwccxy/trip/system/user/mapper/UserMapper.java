package cn.lwccxy.trip.system.user.mapper;

import cn.lwccxy.trip.system.user.domain.entity.UserEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
    @Select("SELECT * FROM t_user WHERE username = #{username}")
    UserEntity selectByUsername(@Param("username") String username);

    @Select("SELECT * FROM t_user WHERE id = #{id} FOR UPDATE")
    UserEntity selectByIdForUpdate(@Param("id") Long id);
}
