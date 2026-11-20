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
            // “给不给报商务舱”“是否报销商务舱”属于资格政策咨询，不是报销办理请求。
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
                    Expressions.NEGATED_APPLICATION, Expressions.EXISTING_APPLICATION_ACTION),
            // “给我开个差旅申请单”是创建请求，口语中的“开”不能落入申请查询。
            new IntentRule(Expressions.COLLOQUIAL_CREATE_APPLICATION,
                    Expressions.APPROVAL_STATUS, Expressions.POLICY, Expressions.PAST_APPLICATION,
                    Expressions.NEGATED_APPLICATION, Expressions.EXISTING_APPLICATION_ACTION),
            new IntentRule("(?:出差|差旅).{0,8}(?:申请|审批)(?:单|表)?.{0,8}(?:创建|新建|发起|提交|填写)",
                    Expressions.APPROVAL_STATUS, Expressions.POLICY, Expressions.PAST_APPLICATION,
                    Expressions.NEGATED_APPLICATION, Expressions.EXISTING_APPLICATION_ACTION),
            new IntentRule("(?:新建|创建|发起|提交|填写)(?:一份|一个|一张|一下)?(?:新的?|出差|差旅)?申请(?:单|表)?",
                    Expressions.APPROVAL_STATUS, Expressions.POLICY, Expressions.PAST_APPLICATION,
                    Expressions.NEGATED_APPLICATION, Expressions.EXISTING_APPLICATION_ACTION,
                    "请假|休假|贷款|信用卡|采购|入职|离职|报销"),
            new IntentRule("^(?:帮我)?(?:出差|差旅)申请(?:单|表)?$")),

    /** 查询已有出差申请单或行程单的列表、详情和申请状态。 */
    TRIP_APPLICATION_QUERY("TRIP_APPLICATION_QUERY", "TripOrderManagementAgent",
            new IntentRule("(?:查|查询|查看|看看|看下|了解|获取|列出|找|找出|展示|打开|发我|给我).{0,16}(?:我的|本人|当前|已有|之前|上次)?(?:出差|差旅)?(?:申请(?:单|表)?|行程单)",
                    Expressions.APPROVAL_STATUS, Expressions.POLICY, Expressions.VENDOR_ORDER_OBJECT,
                    Expressions.NEW_APPLICATION_ACTION),
            new IntentRule("(?:我的|本人|当前|已有|之前|上次).{0,8}(?:出差|差旅)?(?:申请(?:单|表)?|行程单).{0,10}(?:详情|信息|列表|记录|内容|状态|在哪|有哪些|给我|看看|查)",
                    Expressions.APPROVAL_STATUS, Expressions.POLICY, Expressions.VENDOR_ORDER_OBJECT,
                    Expressions.NEW_APPLICATION_ACTION),
            // 行程单或申请记录后置“发我/给我”仍表示读取已有申请单。
            new IntentRule("(?:申请(?:单|表|记录)|行程单).{0,10}(?:发我|给我|展示|打开|看看|看一下)",
                    Expressions.APPROVAL_STATUS, Expressions.POLICY, Expressions.VENDOR_ORDER_OBJECT,
                    Expressions.NEW_APPLICATION_ACTION, Expressions.EXISTING_APPLICATION_ACTION)),

    /** 修改、取消或重新提交已有出差申请单及行程单。 */
    TRIP_APPLICATION_ACTION("TRIP_APPLICATION_ACTION", "TripOrderManagementAgent",
            new IntentRule("(?:修改|变更|编辑|更新|取消|撤销|作废|删除|重新提交|再次提交|恢复).{0,20}(?:出差|差旅)?(?:申请(?:单|表)?|行程单|行程)",
                    Expressions.APPROVAL_STATUS, Expressions.POLICY, Expressions.NEGATED_APPLICATION,
                    Expressions.VENDOR_ORDER_OBJECT, Expressions.NEGATED_EXISTING_APPLICATION_ACTION),
            new IntentRule("(?:出差|差旅)?(?:申请(?:单|表)?|行程单|行程).{0,20}(?:修改|变更|编辑|更新|取消|撤销|作废|删除|重新提交|再次提交|恢复)",
                    Expressions.APPROVAL_STATUS, Expressions.POLICY, Expressions.NEGATED_APPLICATION,
                    Expressions.VENDOR_ORDER_OBJECT, Expressions.NEGATED_EXISTING_APPLICATION_ACTION)),

    /** 查询出差申请或审批记录的进度、状态、结果和意见。 */
    APPROVAL_QUERY("APPROVAL_QUERY", "TripOrderManagementAgent",
            new IntentRule(Expressions.APPROVAL_STATUS, Expressions.POLICY, Expressions.NEGATED_APPROVAL),
            new IntentRule("(?:查|查询|查看|看|了解|告诉我).{0,12}审批.{0,12}(?:结果|状态|进度|记录|原因|意见|到哪|哪一步|通过了吗|批下来了吗|被驳回了吗|被拒绝了吗)",
                    Expressions.POLICY, Expressions.NEGATED_APPROVAL),
            new IntentRule("(?:申请|审批).{0,10}(?:通过了吗|批下来了吗|批准了吗|批了吗|被驳回了吗|被拒绝了吗|为什么.{0,4}(?:驳回|拒绝|退回))",
                    Expressions.POLICY, Expressions.NEGATED_APPROVAL),
            new IntentRule("(?:通过|驳回|拒绝|同意|批准).{0,16}(?:申请|审批|申请单).{0,4}(?:吗|么|不|？|\\?)",
                    Expressions.POLICY, Expressions.NEGATED_APPROVAL)),

    // ================ 行程规划智能体 ====================
    /** 查询或比较可用的航班、车次、酒店等差旅资源。 */
    TRAVEL_RESOURCE_QUERY("TRAVEL_RESOURCE_QUERY", "ItineraryPlanningAgent",
            // 资源查询只返回候选资源或比较结果，不代表用户已经要求规划完整行程或下单。
            new IntentRule(Expressions.RESOURCE_QUERY_ACTION + ".{0,24}" + Expressions.TRAVEL_RESOURCE_OBJECT,
                    Expressions.POLICY, Expressions.EXISTING_ORDER, Expressions.POLICY_INQUIRY,
                    Expressions.REIMBURSEMENT_REQUEST, Expressions.NEGATED_SEARCH,
                    Expressions.AFFIRMATIVE_RESOURCE_BOOKING),
            new IntentRule(Expressions.TRAVEL_RESOURCE_OBJECT + ".{0,24}(?:有哪些|有什么|可选|备选|推荐|筛选|多少钱|票价|价格|是否有|有没有|比一比|帮我比|给我比)",
                    Expressions.POLICY, Expressions.EXISTING_ORDER, Expressions.POLICY_INQUIRY,
                    Expressions.REIMBURSEMENT_REQUEST, Expressions.NEGATED_SEARCH,
                    Expressions.AFFIRMATIVE_RESOURCE_BOOKING)),

    /** 规划交通、住宿和日程。 */
    PLAN_ITINERARY("PLAN_ITINERARY", "ItineraryPlanningAgent",
            // 用有限长度间隔连接动作与对象，覆盖“规划一下下周北京的行程”等表达。
            new IntentRule(Expressions.PLANNING_ACTION, Expressions.NEGATED_PLAN),
            new IntentRule("(?:行程|出行|路线|日程).{0,8}(?:规划|安排|方案)", Expressions.NEGATED_PLAN),
            // 资源查询已由 TRAVEL_RESOURCE_QUERY 独立承接，避免“查航班”同时命中规划意图。
            new IntentRule("(?:规划|安排|制定|设计|优化).{0,24}(?:交通|住宿|酒店|航班|机票|火车|高铁|车次|车票|路线|日程|行程)",
                    Expressions.NEGATED_PLAN)),

    // ================== 预订智能体 ===================
    /** 预订机票、车票或酒店。 */
    BOOK_TRAVEL("BOOK_TRAVEL", "BookingAgent",
            new IntentRule("(?<!退)(?:预[订定]|订购|购买|买|订).{0,24}(?:机票|航班|飞机票|火车票|高铁票|动车票|车票|酒店|宾馆|住宿|客房|房间|门票)",
                    Expressions.NEGATED_BOOKING, Expressions.EXISTING_ORDER, Expressions.POLICY_INQUIRY,
                    Expressions.REIMBURSEMENT_REQUEST, "退票|退订|改签|取消"),
            new IntentRule("(?:机票|航班|火车票|高铁票|动车票|车票|酒店|住宿|客房|门票).{0,12}(?:订下来|订好|订上|买下来|买好|下单|买了|订了)",
                    Expressions.NEGATED_BOOKING, Expressions.EXISTING_ORDER, Expressions.POLICY_INQUIRY,
                    Expressions.REIMBURSEMENT_REQUEST, "退票|退订|改签|取消")),
    /** 取消或退订已有机票、火车票、酒店等供应商预订订单。 */
    CANCEL_BOOKING("CANCEL_BOOKING", "BookingAgent",
            // 仅把明确的取消指令路由到预订 Agent；订单查询、改签及退订规则咨询不属于此意图。
            new IntentRule("(?:取消|退订|退票|退掉|退了).{0,20}(?:订单|预订单|预订|预定|酒店|宾馆|机票|航班|飞机票|火车票|高铁票|动车票|车票|住宿|客房|门票)",
                    Expressions.POLICY, Expressions.NEGATED_MANAGEMENT,
                    Expressions.REIMBURSEMENT_ELIGIBILITY, "(?:能不能|可不可以|是否可以|怎么|如何|怎样).{0,8}(?:取消|退订|退票|退掉)"),
            new IntentRule("(?:订单|预订单|预订|预定|酒店|宾馆|机票|航班|飞机票|火车票|高铁票|动车票|车票|住宿|客房|门票).{0,20}(?:取消|退订|退票|退掉|退了)",
                    Expressions.POLICY, Expressions.NEGATED_MANAGEMENT,
                    Expressions.REIMBURSEMENT_ELIGIBILITY, "(?:能不能|可不可以|是否可以|怎么|如何|怎样).{0,8}(?:取消|退订|退票|退掉)"),
            // “退已买的机票”是口语化退票指令，动作和对象之间允许带已购状态描述。
            new IntentRule("退(?:(?:已买|已购|已订|买的|订的|这张|那张|该|我的).{0,16}|一下)?" + Expressions.TRAVEL_RESOURCE_OBJECT,
                    Expressions.POLICY, Expressions.NEGATED_MANAGEMENT, Expressions.REIMBURSEMENT_ELIGIBILITY,
                    "退改签|(?:能不能|可不可以|是否可以|怎么|如何|怎样).{0,8}退|退.{0,20}(?:吗|么)")),

    // ================== 报销智能体 ===================
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
                "(?:能否|是否(?:可以|能|允许)?|可不可以|能不能|给不给).{0,6}报销|"
                        + "(?:(?:能否|是否(?:可以|能|允许)?|可不可以|能不能|给不给).{0,6}报|报不报).{0,8}"
                        + "(?:住宿|机票|航班|车票|火车票|高铁票|酒店|餐费|交通费|打车|商务舱)|"
                        + "可以.{0,6}报销(?:吗|么|不|$)|(?:能|可以)报销.{0,4}(?:多少|多少钱)|"
                        + "(?:住宿|机票|车票|餐费|交通费|打车|商务舱).{0,12}(?:能不能报|能报吗|可以报吗|给不给报|报不报)|报销(?:多少|多少钱)";
        private static final String REIMBURSEMENT_REQUEST =
                "(?:帮我|给我|我要|我想|申请|提交|发起|处理|办理)(?:一下|这次|本次|一笔|一份|一张|先)?(?:报销|报账)|^(?:报销|报账)|(?:查询|查看|查|看).{0,8}(?:报销|发票|票据)|(?:如何|怎么|怎样).{0,8}(?:报销|开票)|(?:开具|补开|申请).{0,8}(?:发票|票据)|(?:发票|票据).{0,8}(?:怎么|如何|能否|是否|开具|开票)";
        private static final String APPROVAL_STATUS =
                "(?:审批|申请.{0,6}审批).{0,12}(?:进度|状态|结果|记录|到哪|哪一步|通过了吗|批下来|卡在)"
                        + "|申请.{0,12}(?:进度|结果|到哪|哪一步|通过了吗|批下来|卡在)";
        private static final String PAST_APPLICATION =
                "(?:上周|上次|昨天|之前|已经|已|曾经).{0,8}(?:提交|发起|创建)";
        private static final String COLLOQUIAL_CREATE_APPLICATION =
                "(?:开具|开(?:一?[个份张]|一下|新的?)).{0,20}(?:出差|差旅)申请(?:单|表)?";
        private static final String NEW_APPLICATION_ACTION =
                COLLOQUIAL_CREATE_APPLICATION + "|(?:新建|创建|发起|提交|填写|填一?份).{0,20}(?:出差|差旅)?申请(?:单|表)?";
        private static final String EXISTING_APPLICATION_ACTION =
                "(?:修改|变更|编辑|更新|取消|撤销|作废|删除|重新提交|再次提交|恢复).{0,20}(?:出差|差旅)?(?:申请(?:单|表)?|行程单|行程)"
                        + "|(?:出差|差旅)?(?:申请(?:单|表)?|行程单|行程).{0,20}(?:修改|变更|编辑|更新|取消|撤销|作废|删除|重新提交|再次提交|恢复)";
        private static final String NEGATED_EXISTING_APPLICATION_ACTION =
                "(?:不要|不用|无需|不必|别|暂不|先不|不想|不需要).{0,8}(?:修改|变更|编辑|更新|取消|撤销|作废|删除|重新提交|再次提交|恢复)";
        private static final String VENDOR_ORDER_OBJECT =
                "订单|预订单|机票|航班|火车票|高铁票|动车票|车票|酒店|宾馆|住宿|客房|门票|预订|预定";
        private static final String EXISTING_ORDER =
                "订单|行程单|出票|已订|已购|已买|(?:已经|之前|上次|昨天|前天).{0,8}(?:预订|订好|订过|订的|买的|购买|买了)";
        private static final String PLANNING_ACTION =
                "(?:规划|安排|制定|设计|优化).{0,24}(?:行程|出行|路线|日程|交通|住宿|餐饮)";
        private static final String RESOURCE_ACTION =
                "(?:查询|查|搜索|搜|找|看看|看下|对比|比较|推荐|筛选).{0,24}(?:机票|航班|飞机票|火车|高铁|动车|车次|车票|酒店|宾馆|住宿|客房|交通路线|出行路线|门票)";
        private static final String RESOURCE_QUERY_ACTION =
                "(?:查询|查|搜索|搜|找|查看|看看|看下|对比|比较|推荐|筛选)";
        private static final String TRAVEL_RESOURCE_OBJECT =
                "(?:机票|航班|飞机票|火车|高铁|动车|车次|车票|酒店|宾馆|住宿|客房|门票|交通路线|出行路线)";
        private static final String NEGATED_BOOKING =
                "(?:不要|不用|无需|不必|别|暂不|先不|不想|不需要|不能|不允许|没打算).{0,8}(?:预[订定]|订|购买|买|下单)|不(?:再|先)?(?:预[订定]|订|购买|买|下单)|(?:还没|尚未|没有|没)(?:预订|订过|订好|订|买过|买)";
        private static final String AFFIRMATIVE_RESOURCE_BOOKING =
                "^(?!.*(?:" + NEGATED_BOOKING + ")).*(?:(?:预[订定]|订购|购买|买|订).{0,24}"
                        + TRAVEL_RESOURCE_OBJECT + "|" + TRAVEL_RESOURCE_OBJECT
                        + ".{0,12}(?:订下来|订好|订上|订了|买下来|买好|买了|下单|(?<!不)(?:能|可以).{0,3}(?:预[订定]|订购|购买|订|买)))";
        private static final String NEGATED_MANAGEMENT =
                "(?:不要|不用|无需|不必|别|暂不|先不|不想|不需要|不能).{0,8}(?:取消|改签|退)|不(?:再|先)?(?:取消|改签|退)";
        private static final String NEGATED_APPLICATION =
                "(?:不要|不用|无需|不必|别|暂不|先不|不想|不需要).{0,8}(?:新建|创建|发起|提交|填写|申请)|(?:不开|别开|先不开).{0,12}(?:出差|差旅)申请";
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
