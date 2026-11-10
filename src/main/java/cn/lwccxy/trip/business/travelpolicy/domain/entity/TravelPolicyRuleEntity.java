package cn.lwccxy.trip.business.travelpolicy.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 差旅政策规则数据库实体。 */
@Data
@TableName("travel_policy_rule")
public class TravelPolicyRuleEntity {

    /** 自增主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 职级区间下限。 */
    private Integer levelMin;

    /** 职级区间上限，99 表示无上限。 */
    private Integer levelMax;

    /** 城市等级。 */
    private String cityTier;

    /** 允许的最高机票舱位。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String flightClass;

    /** 允许的最高高铁座位。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String trainSeatClass;

    /** 酒店每晚上限，单位为元。 */
    private Double hotelLimit;

    /** 酒店星级上限。 */
    private Integer hotelStarLimit;

    /** 餐补每日上限，单位为元。 */
    private Double dailyMealLimit;

    /** 交通补贴每日上限，单位为元。 */
    private Double dailyTransportLimit;

    /** 超过此金额需要审批。 */
    private Double approvalThreshold;

    /** 提前预订最少天数。 */
    private Integer advanceBookingDays;
}
