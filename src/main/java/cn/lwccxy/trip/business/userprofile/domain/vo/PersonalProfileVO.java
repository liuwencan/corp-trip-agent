package cn.lwccxy.trip.business.userprofile.domain.vo;

import cn.lwccxy.trip.system.user.domain.vo.UserVO;
import lombok.Data;
import lombok.ToString;

/** 前端个人中心视图，账户信息与可选出行档案统一读取。 */
@Data
@ToString(onlyExplicitlyIncluded = true)
public class PersonalProfileVO {
    private UserVO account;
    private boolean profileExists;
    private UserProfileVO profile;
}
