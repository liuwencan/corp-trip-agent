package cn.lwccxy.trip.business.userprofile.domain.vo;

import lombok.Data;

/** 用户档案视图对象，供经过身份校验的业务工具使用。 */
@Data
public class UserProfileVO {

    /** 用户常驻城市。 */
    private String baseCity;

    /** 用户职级。 */
    private String level;

    /** 酒店预订使用的姓名拼音。 */
    private String namePinyin;

    /** 酒店预订联系人邮箱。 */
    private String email;

    /** 机票预订使用的中文姓名。 */
    private String chineseName;

    /** 证件类型编码。 */
    private Integer idType;

    /** 机票和火车票预订使用的证件号码。 */
    private String idNumber;

    /** 预订联系人手机号。 */
    private String phone;

    /** 性别代码：M 或 F。 */
    private String gender;
}
