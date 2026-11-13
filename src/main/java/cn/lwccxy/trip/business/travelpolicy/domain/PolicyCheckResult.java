package cn.lwccxy.trip.business.travelpolicy.domain;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 差旅订单政策校验结果。 */
@Data
public class PolicyCheckResult {

    /** 是否符合政策。 */
    private boolean compliant;
    /** 政策违规说明。 */
    private List<String> violations = new ArrayList<>();
    /** 后续处理建议。 */
    private List<String> suggestions = new ArrayList<>();

    /**
     * 创建默认合规结果。
     *
     * @return 合规结果
     */
    public static PolicyCheckResult compliant() {
        PolicyCheckResult result = new PolicyCheckResult();
        result.setCompliant(true);
        return result;
    }
}
