package cn.lwccxy.trip.business.travelpolicy.domain;

import lombok.Data;

/** 用户在指定目的地可享有的差旅政策。 */
@Data
public class TravelPolicy {

    /** 用户职级。 */
    private String userLevel;
    /** 目的城市。 */
    private String destinationCity;
    /** 目的城市等级。 */
    private String cityTier;
    /** 允许的最高机票舱位。 */
    private String flightClass;
    /** 酒店每晚价格上限。 */
    private double hotelLimit;
    /** 酒店星级上限。 */
    private int hotelStarLimit;
    /** 每日餐补上限。 */
    private double dailyMealLimit;
    /** 每日交通补贴上限。 */
    private double dailyTransportLimit;
    /** 允许的最高火车座席。 */
    private String trainSeatClass;
    /** 需要审批的金额阈值。 */
    private double approvalThreshold;
    /** 最少提前预订天数。 */
    private int advanceBookingDays;
}
