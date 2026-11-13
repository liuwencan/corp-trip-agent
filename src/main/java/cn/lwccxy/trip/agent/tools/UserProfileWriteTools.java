package cn.lwccxy.trip.agent.tools;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.business.userprofile.domain.entity.UserProfileEntity;
import cn.lwccxy.trip.business.userprofile.service.UserProfileService;
import com.alibaba.fastjson2.JSON;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/** 提供当前登录用户个人档案的受控局部写入工具。 */
@Component
public class UserProfileWriteTools {

    private final UserProfileService userProfileService;

    /** 创建个人信息写入工具。 */
    public UserProfileWriteTools(UserProfileService userProfileService) {
        this.userProfileService = userProfileService;
    }

    /** 保存当前用户明确提供的个人档案字段，未提供字段保持原值。 */
    @Tool(name = "update_current_user_profile", description = "仅在用户明确要求保存或修改本人个人档案时调用。只更新用户本次明确提供的字段，未提供字段保持原值。当前用户身份由系统会话注入，不接受 userId。不得根据记忆或推测填写证件号码、手机号、姓名拼音等个人信息。")
    public String updateCurrentUserProfile(
            AgentRuntimeContext context,
            @ToolParam(name = "baseCity", description = "可选，常驻城市，最多 128 字符", required = false) String baseCity,
            @ToolParam(name = "level", description = "可选，职级，例如 P5、P7，最多 16 字符", required = false) String level,
            @ToolParam(name = "namePinyin", description = "可选，酒店预订使用的姓名拼音，最多 128 字符", required = false) String namePinyin,
            @ToolParam(name = "email", description = "可选，预订联系人邮箱，最多 128 字符", required = false) String email,
            @ToolParam(name = "chineseName", description = "可选，中文姓名，最多 64 字符", required = false) String chineseName,
            @ToolParam(name = "idType", description = "可选，证件类型编码 0 到 9", required = false) Integer idType,
            @ToolParam(name = "idNumber", description = "可选，证件号码，最多 64 字符；不得写入日志或摘要", required = false) String idNumber,
            @ToolParam(name = "phone", description = "可选，手机号，最多 32 字符；不得写入日志或摘要", required = false) String phone,
            @ToolParam(name = "gender", description = "可选，性别代码 M 或 F", required = false) String gender) {
        String timestamp = OffsetDateTime.now().toString();
        try {
            String userId = requireUserId(context);
            UserProfileEntity changes = new UserProfileEntity();
            changes.setBaseCity(baseCity);
            changes.setLevel(level);
            changes.setNamePinyin(namePinyin);
            changes.setEmail(email);
            changes.setChineseName(chineseName);
            changes.setIdType(idType);
            changes.setIdNumber(idNumber);
            changes.setPhone(phone);
            changes.setGender(gender);
            userProfileService.updatePartial(userId, changes);
            return JSON.toJSONString(new UserProfileResult(true, null,
                    "个人档案已更新。敏感字段不会在此工具结果或日志中回显。", null, timestamp));
        } catch (IllegalArgumentException exception) {
            return JSON.toJSONString(new UserProfileResult(false, "INVALID_PROFILE", exception.getMessage(), null, timestamp));
        } catch (Exception exception) {
            return JSON.toJSONString(new UserProfileResult(false, "PROFILE_WRITE_FAILED",
                    "保存个人档案失败，请稍后重试。", null, timestamp));
        }
    }

    /** 校验 AgentScope 注入的当前会话身份。 */
    private String requireUserId(AgentRuntimeContext context) {
        if (context == null || context.getUserId() == null || context.getUserId().isBlank()) {
            throw new IllegalArgumentException("当前会话缺少登录用户信息，请重新登录后重试。");
        }
        return context.getUserId().trim();
    }

    /** 个人档案写入结果。 */
    private record UserProfileResult(boolean success, String errorCode, String message,
                                     Object data, String timestamp) {
    }
}
