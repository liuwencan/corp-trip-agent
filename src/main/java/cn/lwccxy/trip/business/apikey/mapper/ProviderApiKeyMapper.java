package cn.lwccxy.trip.business.apikey.mapper;

import cn.lwccxy.trip.business.apikey.domain.entity.ProviderApiKeyEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

/** 用户第三方服务商 API Key 数据访问接口。 */
@Mapper
public interface ProviderApiKeyMapper extends BaseMapper<ProviderApiKeyEntity> {

    /** 查询当前用户拥有的指定凭证。 */
    @Select("SELECT * FROM user_provider_api_key WHERE id=#{id} AND user_id=#{userId}")
    ProviderApiKeyEntity selectOwned(@Param("userId") Long userId, @Param("id") Long id);

    /** 按用户归属更新服务商凭证，避免仅凭主键越权修改。 */
    @Update("""
            UPDATE user_provider_api_key
            SET provider_code=#{providerCode}, api_key_ciphertext=#{apiKeyCiphertext},
                status=#{status}, remark=#{remark}, updated_by=#{updatedBy},
                update_time=CURRENT_TIMESTAMP(6)
            WHERE id=#{id} AND user_id=#{userId}
            """)
    int updateOwned(@Param("userId") Long userId,
                    @Param("id") Long id,
                    @Param("providerCode") String providerCode,
                    @Param("apiKeyCiphertext") String apiKeyCiphertext,
                    @Param("status") String status,
                    @Param("remark") String remark,
                    @Param("updatedBy") Long updatedBy);

    /** 按用户归属删除服务商凭证，避免仅凭主键越权删除。 */
    @Delete("DELETE FROM user_provider_api_key WHERE id=#{id} AND user_id=#{userId}")
    int deleteOwned(@Param("userId") Long userId, @Param("id") Long id);
}
