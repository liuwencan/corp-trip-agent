package cn.lwccxy.trip.agent.enums;

import cn.lwccxy.trip.agent.intent.IntentRule;
import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 企业差旅助手支持的业务意图类型。 */
@Getter
public enum IntentType {
    // ================ GeneralInfoAgent 通用信息智能体 ===================
    /** 查询差旅政策、制度和标准。 */
    TRAVEL_POLICY_QUERY("TRAVEL_POLICY_QUERY", "GeneralInfoAgent",
            // 政策名词支持插入业务对象；“按标准订酒店”中的标准只是操作条件，不是政策查询。
            new IntentRule(Expressions.POLICY, Expressions.POLICY_CONDITION, Expressions.NEGATED_POLICY),
            new IntentRule("(?:餐补|餐费补贴|差旅补贴|住宿补贴|交通补贴)", Expressions.POLICY_CONDITION,
                    Expressions.NEGATED_POLICY),
            new IntentRule(Expressions.REIMBURSEMENT_ELIGIBILITY, Expressions.NEGATED_POLICY),
            new IntentRule("(?:报销金额|报销额度|报销上限).{0,6}(?:多少|标准|规定|限制)|报销额度(?:是多少)?",
                    "(?:我的|这笔|本次|这次|到账|实际|已报销)", Expressions.NEGATED_POLICY)),
    /** 查询天气、目的地咨询和其他出行信息。 */
    TRAVEL_INFO_QUERY("TRAVEL_INFO_QUERY", "GeneralInfoAgent",
            new IntentRule("天气|气温|降雨|下雨|有雨|天儿.{0,4}(?:咋样|怎么样)", Expressions.NEGATED_INFO),
            new IntentRule("(?:目的地|当地|城市).{0,8}(?:概况|情况|风俗|文化|简介|资讯)|(?:介绍|了解).{0,12}(?:历史|背景|风俗|文化)",
                    Expressions.NEGATED_INFO),
            new IntentRule("景点|景区|周边信息", Expressions.RESOURCE_ACTION, Expressions.PLANNING_ACTION,
                    Expressions.NEGATED_INFO),
            // 交通资源搜索与餐饮安排由行程规划处理，通用信息不再用“交通/餐饮”单词兜底。
            new IntentRule("当地交通|市内交通|交通信息", "机票|航班|高铁|动车|车次|车票|酒店|路线|方案",
                    Expressions.POLICY, Expressions.NEGATED_INFO)),

    // ================ TripOrderManagementAgent 行程单管理智能体 ===================
    /** 创建或提交出差申请。 */
    CREATE_TRIP_APPLICATION("CREATE_TRIP_APPLICATION", "TripOrderManagementAgent",
            // 创建必须带动作；已有申请的状态、审批结果与历史提交不能触发新建。
            new IntentRule("(?:新建|创建|发起|提交|填写|填一?份|申请).{0,20}(?:出差|差旅)(?:申请|审批)?",
                    Expressions.APPROVAL_STATUS, Expressions.POLICY, Expressions.PAST_APPLICATION,
                    Expressions.NEGATED_APPLICATION),
            new IntentRule("(?:出差|差旅).{0,8}(?:申请|审批)(?:单|表)?.{0,8}(?:创建|新建|发起|提交|填写)",
                    Expressions.APPROVAL_STATUS, Expressions.POLICY, Expressions.PAST_APPLICATION,
                    Expressions.NEGATED_APPLICATION),
            new IntentRule("(?:新建|创建|发起|提交|填写)(?:一份|一个|一张|一下)?(?:新的?|出差|差旅)?申请(?:单|表)?",
                    Expressions.APPROVAL_STATUS, Expressions.POLICY, Expressions.PAST_APPLICATION,
                    Expressions.NEGATED_APPLICATION, "请假|休假|贷款|信用卡|采购|入职|离职|报销"),
            new IntentRule("^(?:帮我)?(?:出差|差旅)申请(?:单|表)?$")),
//    /** 查询。 */
//    CREATE_TRIP_APPLICATION("CREATE_TRIP_APPLICATION", "TripOrderManagementAgent",
//            Set.of("出差申请", "差旅申请", "申请出差", "提交出差", "创建出差", "发起出差", "出差审批")),


    /** 规划交通、住宿和日程。 */
    PLAN_ITINERARY("PLAN_ITINERARY", "ItineraryPlanningAgent",
            // 用有限长度间隔连接动作与对象，覆盖“规划一下下周北京的行程”等表达。
            new IntentRule(Expressions.PLANNING_ACTION, Expressions.NEGATED_PLAN),
            new IntentRule("(?:行程|出行|路线|日程).{0,8}(?:规划|安排|方案)", Expressions.NEGATED_PLAN),
            new IntentRule(Expressions.RESOURCE_ACTION, Expressions.POLICY, Expressions.EXISTING_ORDER,
                    "报销|发票|票据|退票|退订|改签|取消", Expressions.NEGATED_SEARCH),
            new IntentRule("(?:机票|航班|火车票|高铁票|车票|酒店|住宿|门票).{0,12}(?:有哪些|有什么|可选|备选|推荐|多少钱|票价|价格)",
                    Expressions.POLICY, Expressions.EXISTING_ORDER, "报销|发票|票据|退票|退订|改签|取消",
                    Expressions.NEGATED_SEARCH)),
    /** 预订机票、车票或酒店。 */
    BOOK_TRAVEL("BOOK_TRAVEL", "BookingAgent",
            new IntentRule("(?<!退)(?:预[订定]|订购|购买|买|订).{0,24}(?:机票|航班|飞机票|火车票|高铁票|动车票|车票|酒店|宾馆|住宿|客房|房间|门票)",
                    Expressions.NEGATED_BOOKING, Expressions.EXISTING_ORDER, Expressions.POLICY_INQUIRY,
                    Expressions.REIMBURSEMENT_REQUEST, "退票|退订|改签|取消"),
            new IntentRule("(?:机票|航班|火车票|高铁票|车票|酒店|住宿|客房|门票).{0,12}(?:订下来|订好|订上|买下来|买好|下单)",
                    Expressions.NEGATED_BOOKING, Expressions.EXISTING_ORDER, Expressions.POLICY_INQUIRY,
                    Expressions.REIMBURSEMENT_REQUEST, "退票|退订|改签|取消")),
    /** 查询、改签、退订或取消已有订单和行程。 */
    MANAGE_ORDER("MANAGE_ORDER", "TripOrderManagementAgent",
            new IntentRule("(?:查询|查看|查|看|打开).{0,16}(?:订单|行程单|预订单)|订单(?:状态|详情|号)|行程单|出票状态",
                    Expressions.POLICY, Expressions.NEGATED_SEARCH, "报销|发票|票据"),
            // “取消”需要明确的差旅对象；不能把“取消会议”等范围外请求识别为订单操作。
            new IntentRule("退订|改签|退票|取消.{0,16}(?:订单|酒店|机票|航班|车票|住宿|行程|出差|差旅|预订|预定)",
                    Expressions.POLICY, Expressions.NEGATED_MANAGEMENT,
                    Expressions.REIMBURSEMENT_ELIGIBILITY),
            new IntentRule("(?:订单|酒店|机票|航班|车票|高铁票|住宿).{0,16}(?:取消|退订|改签|退掉|退了|改到)",
                    Expressions.POLICY, Expressions.NEGATED_MANAGEMENT, Expressions.REIMBURSEMENT_ELIGIBILITY),
            new IntentRule("(?:查|看|确认).{0,20}(?:已订|已购|订的|买的).{0,12}(?:机票|航班|车票|酒店)|(?:机票|航班|车票|酒店).{0,12}(?:有没有出票|是否出票|出票了吗)",
                    Expressions.POLICY, Expressions.NEGATED_SEARCH, "报销|发票|票据")),
    /** 查询或处理审批相关事项。 */
    APPROVAL_QUERY("APPROVAL_QUERY", "ApprovalAgent",
            new IntentRule(Expressions.APPROVAL_STATUS, Expressions.POLICY, Expressions.NEGATED_APPROVAL),
            new IntentRule("(?:通过|驳回|拒绝|同意|批准).{0,12}(?:出差|差旅)?申请",
                    Expressions.POLICY, Expressions.NEGATED_APPROVAL),
            new IntentRule("(?:申请|审批).{0,10}(?:通过了吗|批下来了吗|批准了吗|批了吗|为什么.{0,4}(?:驳回|拒绝|退回))",
                    Expressions.POLICY, Expressions.NEGATED_APPROVAL)),
    /** 处理差旅费用和报销事项。 */
    REIMBURSEMENT("REIMBURSEMENT", "ReimbursementAgent",
            // 报销仍覆盖流程、材料和发票，但政策标准与可报销范围优先归政策查询。
            new IntentRule("报销|发票|票据|报账", Expressions.POLICY, Expressions.REIMBURSEMENT_ELIGIBILITY,
                    Expressions.NEGATED_REIMBURSEMENT,
                    "(?:预[订定]|订|购买|买).{0,12}(?:可以|能|允许)报销的",
                    "(?:发票|票据).{0,8}(?:开好|开完|开了|还没开|没开).{0,8}(?:帮我|给我|请).{0,4}(?:预[订定]|订|购买|买)")),
    /** 用户仅进行问候等轻量闲聊。 */
    CHAT("CHAT", "MasterAgent"),
    /** 暂未识别的业务意图。 */
    UNKNOWN("UNKNOWN", "MasterAgent");

    /** 识别出的目标业务意图英文编码。 */
    private final String targetIntent;
    /** 该意图对应的目标智能体英文标识。 */
    private final String targetAgent;
    /** 该意图的正向表达式集合，供原有调用方查看关键词配置。 */
    private final Set<String> regexKeywords;

    /** 同一意图可包含多条独立规则，每条规则拥有自己的排除条件。 */
    private final List<IntentRule> rules;

    /**
     * 绑定意图路由与规则，并保留兼容的正向关键词视图。
     *
     * @param targetIntent 业务意图编码
     * @param targetAgent 目标智能体标识
     * @param rules 本意图的全部规则，任意一条有效即可命中
     */
    IntentType(String targetIntent, String targetAgent, IntentRule... rules) {
        this.targetIntent = targetIntent;
        this.targetAgent = targetAgent;
        // 规则在枚举初始化时固化，识别期间只读取，避免不同请求相互影响。
        this.rules = List.of(rules);
        this.regexKeywords = Arrays.stream(rules).map(IntentRule::getPositiveRegex)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 复用容易混淆的业务边界；由各条规则明确选择需要的排除条件。 */
    private static final class Expressions {
        private static final String POLICY =
                "(?:差旅|出差|报销|住宿|酒店|交通|餐饮|费用|机票|车票|火车票|舱位|发票|票据|审批|申请|预订|退改签|改签|退票).{0,8}(?:政策|制度|规定|标准|额度|上限)";
        private static final String POLICY_CONDITION =
                "(?:按(?:照)?|依照|遵循|符合|满足).{0,10}(?:政策|制度|规定|标准)";
        private static final String POLICY_INQUIRY =
                "(?:政策|制度|规定|标准|额度|上限).{0,6}(?:是什么|多少|怎么|如何|有哪些|要求)|(?:查询|查|了解|咨询).{0,12}(?:政策|制度|规定|标准|额度|上限)";
        private static final String REIMBURSEMENT_ELIGIBILITY =
                "(?:能否|是否可以|可不可以|能不能|是否允许).{0,6}报销|可以.{0,6}报销(?:吗|么|不|$)|(?:能|可以)报销.{0,4}(?:多少|多少钱)|(?:住宿|机票|车票|餐费|交通费|打车|商务舱).{0,12}(?:能不能报|能报吗|可以报吗)|报销(?:多少|多少钱)";
        private static final String REIMBURSEMENT_REQUEST =
                "(?:帮我|给我|我要|我想|申请|提交|发起|处理|办理)(?:一下|这次|本次|一笔|一份|一张|先)?(?:报销|报账)|^(?:报销|报账)|(?:查询|查看|查|看).{0,8}(?:报销|发票|票据)|(?:如何|怎么|怎样).{0,8}(?:报销|开票)|(?:开具|补开|申请).{0,8}(?:发票|票据)|(?:发票|票据).{0,8}(?:怎么|如何|能否|是否|开具|开票)";
        private static final String APPROVAL_STATUS =
                "(?:审批|申请).{0,12}(?:进度|状态|结果|记录|到哪|哪一步|通过了吗|批下来|卡在)";
        private static final String PAST_APPLICATION =
                "(?:上周|上次|昨天|之前|已经|已|曾经).{0,8}(?:提交|发起|创建)";
        private static final String EXISTING_ORDER =
                "订单|行程单|出票|已订|已购|(?:已经|之前|上次|昨天|前天).{0,8}(?:预订|订好|订过|订的|买的|购买)";
        private static final String PLANNING_ACTION =
                "(?:规划|安排|制定|设计|优化).{0,24}(?:行程|出行|路线|日程|交通|住宿|餐饮)";
        private static final String RESOURCE_ACTION =
                "(?:查询|查|搜索|搜|找|看看|看下|对比|比较|推荐|筛选).{0,24}(?:机票|航班|飞机票|火车|高铁|动车|车次|车票|酒店|宾馆|住宿|客房|交通路线|出行路线|门票)";
        private static final String NEGATED_BOOKING =
                "(?:不要|不用|无需|不必|别|暂不|先不|不想|不需要|不能|不允许|没打算).{0,8}(?:预[订定]|订|购买|买|下单)|不(?:再|先)?(?:预[订定]|订|购买|买|下单)|(?:还没|尚未|没有|没)(?:预订|订过|订好|订|买过|买)";
        private static final String NEGATED_MANAGEMENT =
                "(?:不要|不用|无需|不必|别|暂不|先不|不想|不需要|不能).{0,8}(?:取消|退订|改签|退票|退掉|退了)|不(?:再|先)?(?:取消|退订|改签|退票)";
        private static final String NEGATED_APPLICATION =
                "(?:不要|不用|无需|不必|别|暂不|先不|不想|不需要).{0,8}(?:新建|创建|发起|提交|填写|申请)";
        private static final String NEGATED_PLAN =
                "(?:不要|不用|无需|不必|别|暂不|先不|不想|不需要).{0,8}(?:规划|安排|制定|设计|优化)";
        private static final String NEGATED_SEARCH =
                "(?:不要|不用|无需|不必|别|暂不|先不|不想|不需要|不).{0,6}(?:查询|查|搜索|搜|找|看|推荐|对比|比较)";
        private static final String NEGATED_INFO =
                "(?:不要|不用|无需|不必|别|暂不|先不|不想|不需要|不).{0,8}(?:查|看|了解|介绍|天气|景点|交通信息)";
        private static final String NEGATED_POLICY =
                "(?:不要|不用|无需|不必|别|暂不|先不|不想|不需要).{0,8}(?:查|看|了解|政策|标准|制度|规定)";
        private static final String NEGATED_APPROVAL =
                "(?:不要|不用|无需|不必|别|暂不|先不|不想|不需要).{0,8}(?:查|看|通过|驳回|拒绝|同意|批准|审批)";
        private static final String NEGATED_REIMBURSEMENT =
                "(?:不要|不用|无需|不必|别|暂不|先不|不想|不需要).{0,8}(?:报销|报账|开发票|开票)";
    }
}
