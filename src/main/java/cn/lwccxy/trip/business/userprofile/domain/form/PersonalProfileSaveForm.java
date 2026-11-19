package cn.lwccxy.trip.business.userprofile.domain.form;

import cn.lwccxy.trip.business.userprofile.enums.ProfileField;
import cn.lwccxy.trip.business.userprofile.enums.ProfileGender;
import cn.lwccxy.trip.business.userprofile.enums.ProfileIdType;
import cn.lwccxy.trip.business.userprofile.enums.ProfileLevel;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

import java.util.Set;

/** 当前用户资料局部保存请求；未提供和空值字段不更新，清空使用字段白名单。 */
@Data
@ToString(onlyExplicitlyIncluded = true)
public class PersonalProfileSaveForm {
    @Size(max = 20)
    @Pattern(regexp = "(?s).*\\S.*", message = "昵称不能为空白")
    private String nickName;

    @Size(max = 255)
    @Pattern(regexp = "https?://[^\\s]+", message = "头像必须是 HTTP 或 HTTPS 地址")
    private String avatar;

    @Size(max = 128)
    @Pattern(regexp = "(?s).*\\S.*", message = "常驻城市不能为空白")
    private String baseCity;

    private ProfileLevel level;

    @Size(max = 128)
    @Pattern(regexp = "[A-Za-z]+(?: +[A-Za-z]+)+", message = "姓名拼音应以空格分隔姓和名")
    private String namePinyin;

    @Size(max = 128)
    @Email
    @Pattern(regexp = "[^\\s@]+@[^\\s@]+\\.[^\\s@]+", message = "邮箱格式无效")
    private String email;

    @Size(max = 64)
    @Pattern(regexp = "(?s).*\\S.*", message = "中文姓名不能为空白")
    private String chineseName;

    private ProfileIdType idType;

    @Size(max = 64)
    @Pattern(regexp = "(?s).*\\S.*", message = "证件号码不能为空白")
    private String idNumber;

    @Size(min = 7, max = 32)
    @Pattern(regexp = "(?=.*[0-9])[+0-9()\\- ]{7,32}", message = "手机号格式无效")
    private String phone;

    private ProfileGender gender;

    @Size(max = 11)
    private Set<@NotNull ProfileField> clearFields;

    /** 拒绝身份、权限和拼写错误字段，防止前端误以为未开放的属性已被保存。 */
    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("请求包含未开放的个人资料字段。");
    }
}
