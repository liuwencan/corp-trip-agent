package cn.lwccxy.trip.business.apikey.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 用户第三方服务商 API Key 数据库实体。 */
@Data
@TableName("user_provider_api_key")
public class ProviderApiKeyEntity {

    /** 主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属用户 ID。 */
    private Long userId;

    /** 第三方服务商编码。 */
    private String providerCode;

    /** 应用层加密后的 API Key。 */
    private String apiKeyCiphertext;

    /** 凭证状态。 */
    private String status;

    /** 业务备注。 */
    private String remark;

    /** 创建人用户 ID。 */
    private Long createdBy;

    /** 最后修改人用户 ID。 */
    private Long updatedBy;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;
}
