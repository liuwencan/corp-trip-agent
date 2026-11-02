package cn.lwccxy.trip.agent.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Set;

/** 企业差旅助手支持的业务意图类型。 */
@Getter
@AllArgsConstructor
public enum IntentType {
    /** 查询差旅政策、制度和标准。 */
    TRAVEL_POLICY_QUERY("TRAVEL_POLICY_QUERY", "GeneralInfoAgent",
            Set.of("差旅政策", "出差政策", "差旅制度", "出差制度", "差旅规定", "出差规定", "住宿标准", "交通标准", "餐饮标准")),
    /** 查询天气、目的地咨询和其他出行信息。 */
    TRAVEL_INFO_QUERY("TRAVEL_INFO_QUERY", "GeneralInfoAgent",
            Set.of("天气", "目的地", "景点", "当地交通", "交通信息", "路线", "餐饮", "周边信息")),
    /** 创建或提交出差申请。 */
    CREATE_TRIP_APPLICATION("CREATE_TRIP_APPLICATION", "TripOrderManagementAgent",
            Set.of("出差申请", "差旅申请", "申请出差", "提交出差", "创建出差", "发起出差", "出差审批")),
    /** 规划交通、住宿和日程。 */
    PLAN_ITINERARY("PLAN_ITINERARY", "ItineraryPlanningAgent",
            Set.of("规划行程", "安排出行", "安排路线", "制定行程", "设计路线", "行程规划", "行程安排")),
    /** 预订机票、车票或酒店。 */
    BOOK_TRAVEL("BOOK_TRAVEL", "BookingAgent",
            Set.of("预订机票", "订机票", "购买机票", "预订航班", "订火车票", "购买车票", "预订酒店", "订酒店", "预订住宿")),
    /** 查询、改签、退订或取消已有订单和行程。 */
    MANAGE_ORDER("MANAGE_ORDER", "TripOrderManagementAgent",
            Set.of("查询订单", "查看订单", "订单状态", "行程单", "改签", "退票", "退订", "取消订单", "取消酒店")),
    /** 查询或处理审批相关事项。 */
    APPROVAL_QUERY("APPROVAL_QUERY", "ApprovalAgent",
            Set.of("审批进度", "审批状态", "审批结果", "申请进度", "申请状态", "通过申请", "驳回申请", "拒绝申请")),
    /** 处理差旅费用和报销事项。 */
    REIMBURSEMENT("REIMBURSEMENT", "ReimbursementAgent",
            Set.of("报销", "费用报销", "报销材料", "报销清单", "报销进度", "报销凭证", "票据", "发票")),
    /** 用户仅进行问候等轻量闲聊。 */
    CHAT("CHAT", "MasterAgent", Set.of()),
    /** 暂未识别的业务意图。 */
    UNKNOWN("UNKNOWN", "MasterAgent", Set.of());

    /** 识别出的目标业务意图英文编码。 */
    private final String targetIntent;
    /** 该意图对应的目标智能体英文标识。 */
    private final String targetAgent;
    /** 该意图用于 L1 正则或关键词匹配的用户表达关键词集合。 */
    private final Set<String> regexKeywords;
}
