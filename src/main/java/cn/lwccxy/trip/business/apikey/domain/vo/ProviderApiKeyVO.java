package cn.lwccxy.trip.business.apikey.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 用户第三方服务商 API Key 脱敏返回对象。 */
@Data
public class ProviderApiKeyVO {

    /** 主键。 */
    private Long id;

    /** 归属用户 ID。 */
    private Long userId;

    /** 服务商编码。 */
    private String providerCode;

    /** 凭证状态。 */
    private String status;

    /** 业务备注。 */
    private String remark;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;
}
