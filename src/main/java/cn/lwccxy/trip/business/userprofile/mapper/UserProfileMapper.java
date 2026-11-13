package cn.lwccxy.trip.business.userprofile.mapper;

import cn.lwccxy.trip.business.userprofile.domain.entity.UserProfileEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 用户档案数据库访问接口。 */
@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfileEntity> {
}
