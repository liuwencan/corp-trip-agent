package cn.lwccxy.trip.business.apikey.domain.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 用户第三方服务商 API Key 新增或更新参数。 */
@Getter
@Setter
public class ProviderApiKeySaveForm {

    /** 服务商编码。 */
    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9_-]{1,32}")
    private String providerCode;

    /** 用户提交的原始 API Key，仅用于本次加密保存。 */
    @NotBlank
    @Size(max = 4096)
    private String apiKey;

    /** 凭证状态。 */
    @Pattern(regexp = "ACTIVE|INACTIVE|REVOKED")
    private String status = "ACTIVE";

    /** 业务备注。 */
    @Size(max = 500)
    private String remark;
}
