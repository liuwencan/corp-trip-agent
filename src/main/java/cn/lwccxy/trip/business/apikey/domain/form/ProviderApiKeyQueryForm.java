package cn.lwccxy.trip.business.apikey.domain.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 用户第三方服务商 API Key 分页查询参数。 */
@Getter
@Setter
public class ProviderApiKeyQueryForm {

    /** 页码，从 1 开始。 */
    @Min(1)
    private Long pageNo = 1L;

    /** 每页条数，最大 100。 */
    @Min(1)
    @Max(100)
    private Long pageSize = 20L;

    /** 服务商编码。 */
    @Size(max = 32)
    private String providerCode;

    /** 凭证状态，ALL 表示全部状态。 */
    @Pattern(regexp = "ACTIVE|INACTIVE|REVOKED|ALL")
    private String status = "ALL";
}
