package cn.lwccxy.trip.business.userprofile.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 用户档案数据实体。 */
@Data
@TableName("user_profile")
public class UserProfileEntity {

    /** 用户标识。 */
    @TableId
    private String userId;

    /** 用户常驻城市。 */
    private String baseCity;

    /** 用户职级，例如 P5、P6。 */
    private String level;

    /** 姓名拼音。 */
    private String namePinyin;

    /** 用户邮箱。 */
    private String email;

    /** 中文姓名。 */
    private String chineseName;

    /** 证件类型。 */
    private Integer idType;

    /** 证件号码。 */
    private String idNumber;

    /** 手机号。 */
    private String phone;

    /** 性别。 */
    private String gender;
}
