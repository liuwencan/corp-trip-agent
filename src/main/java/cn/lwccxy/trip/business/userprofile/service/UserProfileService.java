package cn.lwccxy.trip.business.userprofile.service;

import cn.lwccxy.trip.business.userprofile.domain.entity.UserProfileEntity;
import cn.lwccxy.trip.business.userprofile.domain.vo.UserProfileVO;
import cn.lwccxy.trip.business.userprofile.mapper.UserProfileMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.regex.Pattern;

/** 提供当前用户个人档案的受控读取和局部更新能力。 */
@Service
public class UserProfileService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^[+0-9()\\-\\s]{7,32}$");

    private final UserProfileMapper mapper;

    /** 创建用户档案服务。 */
    public UserProfileService(UserProfileMapper mapper) {
        this.mapper = mapper;
    }

    /** 查询指定用户的预订档案，不返回数据库主键以外的内部元数据。 */
    public UserProfileVO get(String userId) {
        String owner = requireUserId(userId);
        UserProfileEntity entity = mapper.selectById(owner);
        if (entity == null) {
            return null;
        }
        UserProfileVO result = new UserProfileVO();
        BeanUtils.copyProperties(entity, result);
        return result;
    }

    /** 校验并局部保存档案字段，未提供的字段保持原值。 */
    @Transactional
    public UserProfileVO updatePartial(String userId, UserProfileEntity changes) {
        String owner = requireUserId(userId);
        validate(changes);
        if (!hasUpdates(changes)) {
            throw new IllegalArgumentException("至少提供一个需要保存的个人资料字段。");
        }

        // 将用户身份作为唯一档案主键，忽略任何由工具参数传入的其他用户标识。
        UserProfileEntity current = mapper.selectById(owner);
        boolean exists = current != null;
        if (current == null) {
            current = new UserProfileEntity();
            current.setUserId(owner);
        }
        mergeNonBlankFields(current, changes);
        try {
            if (!exists) {
                mapper.insert(current);
            } else {
                mapper.updateById(current);
            }
        } catch (DuplicateKeyException exception) {
            // 并发首次写入时主键唯一约束决定唯一档案，冲突方只更新自己提交的非空字段。
            UserProfileEntity latest = mapper.selectById(owner);
            if (latest == null) {
                throw exception;
            }
            mergeNonBlankFields(latest, changes);
            mapper.updateById(latest);
        }
        return get(owner);
    }

    /** 将本次非空变更合并到现有档案，保留用户未提交的字段。 */
    private void mergeNonBlankFields(UserProfileEntity target, UserProfileEntity changes) {
        if (hasText(changes.getBaseCity())) target.setBaseCity(changes.getBaseCity().trim());
        if (hasText(changes.getLevel())) target.setLevel(changes.getLevel().trim().toUpperCase(Locale.ROOT));
        if (hasText(changes.getNamePinyin())) target.setNamePinyin(changes.getNamePinyin().trim());
        if (hasText(changes.getEmail())) target.setEmail(changes.getEmail().trim());
        if (hasText(changes.getChineseName())) target.setChineseName(changes.getChineseName().trim());
        if (changes.getIdType() != null) target.setIdType(changes.getIdType());
        if (hasText(changes.getIdNumber())) target.setIdNumber(changes.getIdNumber().trim());
        if (hasText(changes.getPhone())) target.setPhone(changes.getPhone().trim());
        if (hasText(changes.getGender())) target.setGender(changes.getGender().trim().toUpperCase(Locale.ROOT));
    }

    /** 校验档案字段长度、格式及允许值，避免无效数据进入数据库。 */
    private void validate(UserProfileEntity changes) {
        validateLength(changes.getBaseCity(), 128, "常驻城市");
        validateLength(changes.getLevel(), 16, "职级");
        validateLength(changes.getNamePinyin(), 128, "姓名拼音");
        validateLength(changes.getEmail(), 128, "邮箱");
        validateLength(changes.getChineseName(), 64, "中文姓名");
        validateLength(changes.getIdNumber(), 64, "证件号码");
        validateLength(changes.getPhone(), 32, "手机号");
        validateLength(changes.getGender(), 4, "性别");
        if (hasText(changes.getEmail()) && !EMAIL_PATTERN.matcher(changes.getEmail().trim()).matches()) {
            throw new IllegalArgumentException("邮箱格式无效，请检查后重试。");
        }
        if (hasText(changes.getPhone()) && !PHONE_PATTERN.matcher(changes.getPhone().trim()).matches()) {
            throw new IllegalArgumentException("手机号格式无效，请检查后重试。");
        }
        if (changes.getIdType() != null && (changes.getIdType() < 0 || changes.getIdType() > 9)) {
            throw new IllegalArgumentException("证件类型无效，可选值为 0 到 9。");
        }
        if (hasText(changes.getGender()) && !"M".equalsIgnoreCase(changes.getGender().trim())
                && !"F".equalsIgnoreCase(changes.getGender().trim())) {
            throw new IllegalArgumentException("性别代码无效，只能填写 M 或 F。");
        }
        if (hasText(changes.getLevel()) && !changes.getLevel().trim().matches("(?i)^P\\d{1,2}$")) {
            throw new IllegalArgumentException("职级格式无效，请使用如 P5、P7 的格式。");
        }
    }

    /** 验证字段的数据库长度上限。 */
    private void validateLength(String value, int maxLength, String fieldName) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "过长，最多允许 " + maxLength + " 个字符。");
        }
    }

    /** 验证会话中的用户标识。 */
    private String requireUserId(String userId) {
        if (userId == null || userId.isBlank() || userId.trim().length() > 64) {
            throw new IllegalArgumentException("当前会话缺少有效用户信息，请重新登录后重试。");
        }
        return userId.trim();
    }

    /** 判断可选文本是否包含非空内容。 */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 判断用户是否提交了至少一个可更新字段。 */
    private boolean hasUpdates(UserProfileEntity changes) {
        return hasText(changes.getBaseCity()) || hasText(changes.getLevel())
                || hasText(changes.getNamePinyin()) || hasText(changes.getEmail())
                || hasText(changes.getChineseName()) || changes.getIdType() != null
                || hasText(changes.getIdNumber()) || hasText(changes.getPhone())
                || hasText(changes.getGender());
    }
}
