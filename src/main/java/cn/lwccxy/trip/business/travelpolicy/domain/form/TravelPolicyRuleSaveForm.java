package cn.lwccxy.trip.business.travelpolicy.domain.form;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** 新增或修改差旅政策规则的请求参数。 */
@Getter
@Setter
public class TravelPolicyRuleSaveForm {

    /** 职级区间下限。 */
    @NotNull
    @Min(0)
    @Max(99)
    private Integer levelMin;

    /** 职级区间上限，99 表示无上限。 */
    @NotNull
    @Min(0)
    @Max(99)
    private Integer levelMax;

    /** 城市等级。 */
    @NotNull
    @Pattern(regexp = "一线|新一线|其他")
    @Size(max = 16)
    private String cityTier;

    /** 允许的最高机票舱位。 */
    @Size(max = 32)
    private String flightClass;

    /** 允许的最高高铁座位。 */
    @Size(max = 16)
    private String trainSeatClass;

    /** 酒店每晚上限，单位为元。 */
    @NotNull
    @DecimalMin("0.0")
    private Double hotelLimit = 0.0;

    /** 酒店星级上限。 */
    @NotNull
    @Min(0)
    @Max(5)
    private Integer hotelStarLimit = 4;

    /** 餐补每日上限，单位为元。 */
    @NotNull
    @DecimalMin("0.0")
    private Double dailyMealLimit = 0.0;

    /** 交通补贴每日上限，单位为元。 */
    @NotNull
    @DecimalMin("0.0")
    private Double dailyTransportLimit = 0.0;

    /** 超过此金额需要审批。 */
    @NotNull
    @DecimalMin("0.0")
    private Double approvalThreshold = 0.0;

    /** 提前预订最少天数。 */
    @NotNull
    @Min(0)
    private Integer advanceBookingDays = 3;
}
