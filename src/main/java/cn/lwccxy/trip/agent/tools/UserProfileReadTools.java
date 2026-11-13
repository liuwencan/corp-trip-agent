package cn.lwccxy.trip.agent.tools;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.business.userprofile.domain.vo.UserProfileVO;
import cn.lwccxy.trip.business.userprofile.service.UserProfileService;
import com.alibaba.fastjson2.JSON;
import io.agentscope.core.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/** 提供当前登录用户的个人档案只读工具。 */
@Component
public class UserProfileReadTools {

    private final UserProfileService userProfileService;

    /** 创建个人信息读取工具。 */
    public UserProfileReadTools(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    /** 读取当前会话用户的预订资料，不接受模型指定用户 ID。 */
    @Tool(name = "get_current_user_profile", description = "读取当前登录用户自己的真实个人档案，用于判断途牛预订所需乘客、入住人和联系人字段是否齐全。工具自动使用当前会话身份，不要也不能传 userId。证件号码和手机号属于敏感数据，只能按本次预订必需字段使用，不得在面向用户的回复中复述。")
    public String getCurrentUserProfile(AgentRuntimeContext context) {
        String timestamp = OffsetDateTime.now().toString();
        try {
            String userId = requireUserId(context);
            UserProfileVO profile = userProfileService.get(userId);
            String message = profile == null
                    ? "当前用户尚未建立个人档案，请询问用户是否需要提供或保存预订资料。"
                    : "已读取当前用户档案。仅将其中明确提供且本次预订必需的信息用于供应商订单，不要在回复中复述证件号码或手机号。";
            return JSON.toJSONString(new UserProfileResult(true, null, message, profile, timestamp));
        } catch (IllegalArgumentException exception) {
            return JSON.toJSONString(new UserProfileResult(false, "INVALID_SESSION", exception.getMessage(), null, timestamp));
        } catch (Exception exception) {
            return JSON.toJSONString(new UserProfileResult(false, "PROFILE_READ_FAILED",
                    "读取个人档案失败，请停止自动填入资料并稍后重试。", null, timestamp));
        }
    }

    /** 校验 AgentScope 注入的当前会话身份。 */
    private String requireUserId(AgentRuntimeContext context) {
        if (context == null || context.getUserId() == null || context.getUserId().isBlank()) {
            throw new IllegalArgumentException("当前会话缺少登录用户信息，请重新登录后重试。");
        }
        return context.getUserId().trim();
    }

    /** 个人档案查询结果。 */
    private record UserProfileResult(boolean success, String errorCode, String message,
                                     UserProfileVO data, String timestamp) {
    }
}
