package cn.lwccxy.trip.business.userprofile.service;

import cn.lwccxy.trip.business.userprofile.domain.entity.UserProfileEntity;
import cn.lwccxy.trip.business.userprofile.domain.form.PersonalProfileSaveForm;
import cn.lwccxy.trip.business.userprofile.domain.vo.PersonalProfileVO;
import cn.lwccxy.trip.business.userprofile.domain.vo.ProfileOptionsVO;
import cn.lwccxy.trip.business.userprofile.domain.vo.UserProfileVO;
import cn.lwccxy.trip.business.userprofile.enums.ProfileErrorCode;
import cn.lwccxy.trip.business.userprofile.enums.ProfileField;
import cn.lwccxy.trip.business.userprofile.enums.ProfileGender;
import cn.lwccxy.trip.business.userprofile.enums.ProfileIdType;
import cn.lwccxy.trip.business.userprofile.enums.ProfileLevel;
import cn.lwccxy.trip.business.userprofile.mapper.UserProfileMapper;
import cn.lwccxy.trip.common.auth.CurrentUser;
import cn.lwccxy.trip.common.exception.BizException;
import cn.lwccxy.trip.system.user.domain.entity.UserEntity;
import cn.lwccxy.trip.system.user.enums.UserErrorCode;
import cn.lwccxy.trip.system.user.mapper.UserMapper;
import cn.lwccxy.trip.system.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 为个人中心提供跨账户表和档案表的事务性资料管理。 */
@Service
@RequiredArgsConstructor
public class PersonalProfileService {
    private final CurrentUser currentUser;
    private final UserMapper userMapper;
    private final UserProfileMapper profileMapper;
    private final UserService userService;
    private final Validator validator;

    /** 查询当前登录用户的账户公开信息和可选个人档案。 */
    @Transactional(readOnly = true)
    public PersonalProfileVO get() {
        // 身份只能来自已验证的登录会话，不接收任何目标用户参数。
        Long owner = currentUser.get().getId();
        return read(owner);
    }

    /** 首次创建个人档案；已存在时引导前端使用更新接口。 */
    @Transactional
    public PersonalProfileVO create(PersonalProfileSaveForm form) {
        Map<ProfileField, Object> changes = changes(form);
        UserEntity user = lockOwner();
        String owner = user.getId().toString();
        if (profileMapper.selectById(owner) != null) {
            throw new BizException(ProfileErrorCode.PROFILE_ALREADY_EXISTS);
        }
        // 同一事务提交两张表，任意写入失败均回滚，防止昵称已保存而档案创建失败。
        UserProfileEntity profile = new UserProfileEntity();
        profile.setUserId(owner);
        BeanWrapperImpl properties = new BeanWrapperImpl(profile);
        changes.forEach((field, value) -> {
            if (!accountField(field)) properties.setPropertyValue(field.getCode(), value);
        });
        profileMapper.insert(profile);
        saveAccount(user.getId(), changes);
        return finish(user.getId());
    }

    /** 局部更新当前用户资料，未提交字段保持原值，清空字段显式写入数据库空值。 */
    @Transactional
    public PersonalProfileVO update(PersonalProfileSaveForm form) {
        Map<ProfileField, Object> changes = changes(form);
        UserEntity user = lockOwner();
        String owner = user.getId().toString();
        if (profileMapper.selectById(owner) == null) {
            throw new BizException(ProfileErrorCode.PROFILE_NOT_FOUND);
        }
        // 仅生成本次提交字段的赋值，避免整行更新覆盖其他入口写入的资料。
        UpdateWrapper<UserProfileEntity> update = new UpdateWrapper<>();
        update.eq("user_id", owner);
        boolean profileChanged = false;
        for (var entry : changes.entrySet()) {
            if (!accountField(entry.getKey())) {
                update.set(column(entry.getKey()), entry.getValue());
                profileChanged = true;
            }
        }
        if (profileChanged) profileMapper.update(null, update);
        saveAccount(user.getId(), changes);
        return finish(user.getId());
    }

    /** 幂等删除个人档案并清空昵称和头像，保留登录账户及其凭据。 */
    @Transactional
    public void delete() {
        UserEntity user = lockOwner();
        // 删除和清空共用账户行锁及事务，不触碰账户权限、密码或出差订单。
        profileMapper.deleteById(user.getId().toString());
        Map<ProfileField, Object> changes = new EnumMap<>(ProfileField.class);
        changes.put(ProfileField.NICK_NAME, null);
        changes.put(ProfileField.AVATAR, null);
        saveAccount(user.getId(), changes);
        finish(user.getId());
    }

    /** 返回枚举的业务编码及中文名称，供前端生成选项和字段白名单。 */
    public ProfileOptionsVO options() {
        ProfileOptionsVO result = new ProfileOptionsVO();
        result.setLevels(Arrays.stream(ProfileLevel.values())
                .map(value -> new ProfileOptionsVO.OptionVO(value.getCode(), value.getLabel())).toList());
        result.setGenders(Arrays.stream(ProfileGender.values())
                .map(value -> new ProfileOptionsVO.OptionVO(value.getCode(), value.getLabel())).toList());
        result.setIdTypes(Arrays.stream(ProfileIdType.values())
                .map(value -> new ProfileOptionsVO.OptionVO(value.getCode(), value.getLabel())).toList());
        result.setEditableFields(Arrays.stream(ProfileField.values())
                .map(value -> new ProfileOptionsVO.OptionVO(value.getCode(), value.getLabel())).toList());
        return result;
    }

    /** 加锁前使用已验证的身份，账户行锁用于串行化个人中心和 Agent 的档案写入。 */
    private UserEntity lockOwner() {
        UserEntity user = userMapper.selectByIdForUpdate(currentUser.get().getId());
        if (user == null) throw new BizException(UserErrorCode.AUTH_NOT_LOGIN);
        return user;
    }

    /** 重新读取数据库中的安全视图，避免返回密码哈希和凭据版本。 */
    private PersonalProfileVO read(Long owner) {
        UserEntity user = userMapper.selectById(owner);
        if (user == null) throw new BizException(UserErrorCode.AUTH_NOT_LOGIN);
        PersonalProfileVO result = new PersonalProfileVO();
        result.setAccount(userService.toVO(user));
        UserProfileEntity profile = profileMapper.selectById(owner.toString());
        result.setProfileExists(profile != null);
        if (profile != null) {
            UserProfileVO view = new UserProfileVO();
            BeanUtils.copyProperties(profile, view);
            result.setProfile(view);
        }
        return result;
    }

    /** 写入后同步本请求的身份缓存；后续请求和 Agent 均直接读取数据库中的档案。 */
    private PersonalProfileVO finish(Long owner) {
        PersonalProfileVO result = read(owner);
        UserEntity cached = currentUser.get();
        cached.setNickName(result.getAccount().getNickName());
        cached.setAvatar(result.getAccount().getAvatar());
        cached.setUpdateTime(result.getAccount().getUpdateTime());
        return result;
    }

    /** 仅更新账户的两个资料字段，使用显式赋值支持清空且避免覆盖凭据。 */
    private void saveAccount(Long owner, Map<ProfileField, Object> changes) {
        UpdateWrapper<UserEntity> update = new UpdateWrapper<>();
        update.eq("id", owner);
        boolean changed = false;
        for (var entry : changes.entrySet()) {
            if (accountField(entry.getKey())) {
                update.set(column(entry.getKey()), entry.getValue());
                changed = true;
            }
        }
        if (changed) userMapper.update(null, update);
    }

    /** 统一校验并转换表单，将枚举转换成已有表结构使用的编码。 */
    private Map<ProfileField, Object> changes(PersonalProfileSaveForm form) {
        if (form == null || !validator.validate(form).isEmpty()) {
            throw new BizException(ProfileErrorCode.INVALID_PROFILE);
        }
        Map<ProfileField, Object> values = new EnumMap<>(ProfileField.class);
        put(values, ProfileField.NICK_NAME, form.getNickName());
        put(values, ProfileField.AVATAR, form.getAvatar());
        put(values, ProfileField.BASE_CITY, form.getBaseCity());
        put(values, ProfileField.LEVEL, form.getLevel() == null ? null : form.getLevel().getCode());
        put(values, ProfileField.NAME_PINYIN, form.getNamePinyin() == null ? null
                : form.getNamePinyin().replaceAll(" +", " ").toUpperCase(Locale.ROOT));
        put(values, ProfileField.EMAIL, form.getEmail());
        put(values, ProfileField.CHINESE_NAME, form.getChineseName());
        put(values, ProfileField.ID_TYPE, form.getIdType() == null ? null : form.getIdType().getCode());
        put(values, ProfileField.ID_NUMBER, form.getIdNumber());
        put(values, ProfileField.PHONE, form.getPhone());
        put(values, ProfileField.GENDER, form.getGender() == null ? null : form.getGender().getCode());
        Set<ProfileField> clears = form.getClearFields() == null ? Set.of() : form.getClearFields();
        // 同一字段同时提交新值和清空指令时拒绝整次写入，避免依赖处理顺序产生歧义。
        for (ProfileField field : clears) {
            if (values.containsKey(field)) throw new BizException(ProfileErrorCode.INVALID_PROFILE);
            values.put(field, null);
        }
        if (values.isEmpty()) throw new BizException(ProfileErrorCode.INVALID_PROFILE);
        return values;
    }

    /** 只收集非空输入，字符串去除首尾空格，不把空值误当作删除指令。 */
    private void put(Map<ProfileField, Object> values, ProfileField field, Object value) {
        if (value != null) values.put(field, value instanceof String text ? text.trim() : value);
    }

    /** 区分账户资料字段和出行档案字段，禁止将权限等属性纳入更新。 */
    private boolean accountField(ProfileField field) {
        return field == ProfileField.NICK_NAME || field == ProfileField.AVATAR;
    }

    /** 仅由受控枚举生成数据库列名，请求中的自由文本不能参与列名拼接。 */
    private String column(ProfileField field) {
        return field.name().toLowerCase(Locale.ROOT);
    }
}
