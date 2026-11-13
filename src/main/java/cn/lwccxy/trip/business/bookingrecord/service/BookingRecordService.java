package cn.lwccxy.trip.business.bookingrecord.service;

import cn.lwccxy.trip.business.bookingrecord.domain.entity.BookingRecordEntity;
import cn.lwccxy.trip.business.bookingrecord.domain.enums.BookingType;
import cn.lwccxy.trip.business.bookingrecord.domain.form.BookingRecordQueryForm;
import cn.lwccxy.trip.business.bookingrecord.domain.form.BookingRecordSaveForm;
import cn.lwccxy.trip.business.bookingrecord.domain.vo.BookingRecordVO;
import cn.lwccxy.trip.business.bookingrecord.enums.BookingRecordErrorCode;
import cn.lwccxy.trip.business.bookingrecord.mapper.BookingRecordMapper;
import cn.lwccxy.trip.common.exception.BizException;
import cn.lwccxy.trip.common.response.PageResponse;
import cn.lwccxy.trip.common.response.Response;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.validation.Valid;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.beans.PropertyDescriptor;
import java.time.LocalDateTime;
import java.util.*;

/** 管理正式预订记录及其用户归属、状态和逻辑删除。 */
@Service
@Validated
public class BookingRecordService {

    private final BookingRecordMapper mapper;

    /** 创建业务预订记录服务。 */
    public BookingRecordService(BookingRecordMapper mapper) {
        this.mapper = mapper;
    }

    /** 创建当前用户的预订记录，并在服务端生成缺省内部预订单号。 */
    @Transactional
    public Response<BookingRecordVO> create(String userId, @Valid BookingRecordSaveForm form) {
        String owner = required(userId);
        validateCreateForm(form);
        BookingRecordEntity entity = new BookingRecordEntity();
        BeanUtils.copyProperties(form, entity);
        entity.setBookingId(newBookingId());
        entity.setUserId(owner);
        // 请求仍使用业务编码，实体使用枚举，需显式转换，避免属性复制时丢失业务类型。
        entity.setBizType(requireBookingType(form.getBizType()));
        entity.setPlatform(form.getPlatform().trim());
        entity.setStatus(normalize(form.getStatus()) == null ? "CREATED" : form.getStatus().trim());
        entity.setCurrency(normalize(form.getCurrency()) == null ? "CNY" : form.getCurrency().trim());
        entity.setDeleted(0);
        validateDetail(entity.getDetail());
        validateTimeRange(entity.getStartTime(), entity.getEndTime());
        try {
            // 主键和业务唯一键由数据库兜底，避免并发创建产生重复预订单号。
            if (mapper.insert(entity) != 1) {
                throw new IllegalStateException("预订记录写入失败。");
            }
        } catch (DuplicateKeyException exception) {
            throw new BizException(BookingRecordErrorCode.INVALID_PARAMETER);
        }
        return Response.success(toVO(mapper.owned(owner, entity.getBookingId())));
    }

    /**
     * 新增内部流程组装的预订记录，并将生成的内部订单号和数据库主键回填到原对象。
     *
     * @param bookingRecord 待新增记录，必须提供用户 ID、业务类型和平台；数据库主键必须为空
     * @return 写入成功时返回 true；校验或写入失败时抛出异常
     * @throws BizException 记录参数无效或内部订单号、平台订单号重复时抛出
     */
    @Transactional
    public boolean save(BookingRecordEntity bookingRecord) {
        if (bookingRecord == null || bookingRecord.getId() != null) {
            throw new BizException(BookingRecordErrorCode.INVALID_PARAMETER);
        }

        // 用户归属由内部调用方设置，缺失时拒绝落库，避免生成没有归属的预订记录。
        bookingRecord.setUserId(required(bookingRecord.getUserId()));
        if (bookingRecord.getBizType() == null) {
            throw new BizException(BookingRecordErrorCode.INVALID_PARAMETER);
        }
        bookingRecord.setPlatform(required(bookingRecord.getPlatform()));
        String bookingId = normalize(bookingRecord.getBookingId());
        bookingRecord.setBookingId(bookingId == null ? newBookingId() : bookingId);
        String status = normalize(bookingRecord.getStatus());
        bookingRecord.setStatus(status == null ? "CREATED" : status);
        String currency = normalize(bookingRecord.getCurrency());
        bookingRecord.setCurrency(currency == null ? "CNY" : currency);
        bookingRecord.setDeleted(0);
        if (bookingRecord.getBookedAt() == null) {
            bookingRecord.setBookedAt(LocalDateTime.now());
        }
        validateDetail(bookingRecord.getDetail());
        validateTimeRange(bookingRecord.getStartTime(), bookingRecord.getEndTime());

        try {
            // 仅插入新记录，由数据库唯一键防止并发重复落库；MyBatis 回填自增主键。
            if (mapper.insert(bookingRecord) != 1) {
                throw new IllegalStateException("预订记录写入失败。");
            }
        } catch (DuplicateKeyException exception) {
            throw new BizException(BookingRecordErrorCode.INVALID_PARAMETER);
        }
        return true;
    }

    /** 分页查询当前用户的预订记录。 */
    public PageResponse<BookingRecordVO> list(String userId, @Valid BookingRecordQueryForm form) {
        String owner = required(userId);
        if (form == null || form.getPageNo() == null || form.getPageSize() == null
                || form.getPageNo() < 1 || form.getPageNo() > Integer.MAX_VALUE
                || form.getPageSize() < 1 || form.getPageSize() > 100) {
            throw new BizException(BookingRecordErrorCode.INVALID_PARAMETER);
        }
        String bookingId = normalize(form.getBookingId());
        String conversationId = normalize(form.getConversationId());
        String travelOrderId = normalize(form.getTravelOrderId());
        String bizType = normalize(form.getBizType());
        String status = normalize(form.getStatus());
        long total = mapper.countOwned(owner, bookingId, conversationId, travelOrderId, bizType, status);
        List<BookingRecordVO> data = mapper.pageOwned(owner, bookingId, conversationId, travelOrderId,
                        bizType, status, PageResponse.getOffset(form.getPageNo(), form.getPageSize()), form.getPageSize())
                .stream().map(BookingRecordService::toVO).toList();
        return PageResponse.success(data, form.getPageNo(), total, form.getPageSize());
    }

    /** 查询当前用户所属的单条预订记录。 */
    public Response<BookingRecordVO> get(String userId, String bookingId) {
        return Response.success(toVO(requireOwned(required(userId), required(bookingId))));
    }

    /**
     * 按平台、业务类型和外部订单号查询未删除的预订记录，供内部落库流程检查重复订单。
     *
     * @param platform 预订平台，不能为空
     * @param bizType 预订业务类型，查询前转为大写
     * @param externalOrderNo 外部平台订单号，不能为空
     * @return 匹配的预订记录；不存在时返回空 Optional
     * @throws BizException 任一查询条件为空白时抛出参数异常
     */
    public Optional<BookingRecordVO> findByExternalOrder(String platform, String bizType, String externalOrderNo) {
        String provider = required(platform);
        String type = required(bizType).toUpperCase(Locale.ROOT);
        String orderNo = required(externalOrderNo);

        // 三个条件共同对应供应商订单唯一键，排除逻辑删除记录，避免将无效记录视为已预订。
        BookingRecordEntity entity = mapper.selectOne(new LambdaQueryWrapper<BookingRecordEntity>()
                .eq(BookingRecordEntity::getPlatform, provider)
                .eq(BookingRecordEntity::getBizType, type)
                .eq(BookingRecordEntity::getExternalOrderNo, orderNo)
                .eq(BookingRecordEntity::getDeleted, 0));
        return Optional.ofNullable(entity).map(BookingRecordService::toVO);
    }

    /** 在供应商确认取消后，按用户归属和外部订单号更新正式预订状态。 */
    @Transactional
    public boolean markCancelledByExternalOrder(String userId, String bizType, String platform,
                                                String externalOrderNo, String externalStatus) {
        String owner = required(userId);
        String type = required(bizType).toUpperCase(Locale.ROOT);
        String provider = required(platform);
        String orderNo = required(externalOrderNo);
        // 仅记录供应商非敏感状态短文本；本地终态统一设为 CANCELLED。
        return mapper.markCancelledByExternalOrder(owner, provider, type, orderNo,
                safeExternalStatus(externalStatus)) == 1;
    }

    /**
     * 在供应商确认取消后，按用户归属和内部预订单号同步本地取消状态。
     *
     * @param userId 当前登录用户 ID
     * @param bookingId 内部预订单号，不是数据库主键或供应商订单号
     * @return 同步成功的响应；已经取消的记录重复调用仍视为成功
     * @throws BizException 参数无效、记录不属于当前用户或当前状态不允许取消时抛出
     */
    @Transactional
    public Response<Void> cancel(String userId, String bookingId) {
        String owner = required(userId);
        String id = required(bookingId);
        if (id.length() > 64) {
            throw new BizException(BookingRecordErrorCode.INVALID_PARAMETER);
        }

        // 归属、未删除和可取消状态在同一条更新语句中校验，避免并发完成或退款后又被覆盖为取消。
        int affected = mapper.update(null, new LambdaUpdateWrapper<BookingRecordEntity>()
                .eq(BookingRecordEntity::getUserId, owner)
                .eq(BookingRecordEntity::getBookingId, id)
                .eq(BookingRecordEntity::getDeleted, 0)
                .notIn(BookingRecordEntity::getStatus, "CANCELLED", "COMPLETED", "REFUNDED")
                .set(BookingRecordEntity::getStatus, "CANCELLED")
                .set(BookingRecordEntity::getUpdatedAt, LocalDateTime.now()));
        if (affected == 1) {
            return Response.success();
        }
        if (affected != 0) {
            throw new IllegalStateException("取消预订记录影响行数异常。");
        }

        // 未命中更新时才读取状态：隐藏其他用户记录，并让重复取消保持幂等；不修改支付或供应商状态。
        BookingRecordEntity entity = requireOwned(owner, id);
        if ("CANCELLED".equals(entity.getStatus())) {
            return Response.success();
        }
        throw new BizException(BookingRecordErrorCode.INVALID_STATUS);
    }

    /** 更新当前用户所属的预订记录，只覆盖请求中显式提供的字段。 */
    @Transactional
    public Response<BookingRecordVO> update(String userId, String bookingId, @Valid BookingRecordSaveForm form) {
        String owner = required(userId);
        String id = required(bookingId);
        if (form == null) {
            throw new BizException(BookingRecordErrorCode.INVALID_PARAMETER);
        }
        BookingRecordEntity entity = requireOwned(owner, id);
        // 不允许通过普通更新改变内部身份或归属，空值表示保留原字段。
        copyNonNullProperties(form, entity);
        if (form.getBizType() != null) {
            // 编码与枚举类型不同，非空业务类型需要单独转换才能保持部分更新语义。
            entity.setBizType(requireBookingType(form.getBizType()));
        }
        validateDetail(entity.getDetail());
        validateTimeRange(entity.getStartTime(), entity.getEndTime());
        try {
            if (mapper.updateById(entity) != 1) {
                throw new BizException(BookingRecordErrorCode.RESOURCE_NOT_FOUND);
            }
        } catch (DuplicateKeyException exception) {
            throw new BizException(BookingRecordErrorCode.INVALID_PARAMETER);
        }
        return Response.success(toVO(requireOwned(owner, id)));
    }

    /** 逻辑删除当前用户所属的预订记录。 */
    @Transactional
    public Response<Void> delete(String userId, String bookingId) {
        BookingRecordEntity entity = requireOwned(required(userId), required(bookingId));
        entity.setDeleted(1);
        if (mapper.updateById(entity) != 1) {
            throw new BizException(BookingRecordErrorCode.RESOURCE_NOT_FOUND);
        }
        return Response.success();
    }

    /** 校验新建记录的必填字段、JSON 明细和时间范围。 */
    private static void validateCreateForm(BookingRecordSaveForm form) {
        if (form == null || normalize(form.getBizType()) == null || normalize(form.getPlatform()) == null) {
            throw new BizException(BookingRecordErrorCode.INVALID_PARAMETER);
        }
    }

    /** 确认预订明细字段为空或为合法 JSON。 */
    private static void validateDetail(String detail) {
        if (detail != null && !detail.isBlank() && !JSON.isValid(detail)) {
            throw new BizException(BookingRecordErrorCode.INVALID_PARAMETER);
        }
    }

    /** 确认服务结束时间不早于开始时间。 */
    private static void validateTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime != null && endTime != null && endTime.isBefore(startTime)) {
            throw new BizException(BookingRecordErrorCode.INVALID_PARAMETER);
        }
    }

    /** 将请求中非空属性复制到实体，保持部分更新语义。 */
    private static void copyNonNullProperties(Object source, Object target) {
        BeanWrapper wrapper = new BeanWrapperImpl(source);
        String[] nullProperties = Arrays.stream(wrapper.getPropertyDescriptors())
                .map(PropertyDescriptor::getName)
                .filter(name -> wrapper.getPropertyValue(name) == null)
                .toArray(String[]::new);
        BeanUtils.copyProperties(source, target, nullProperties);
    }

    /** 将数据库实体映射为接口或工具使用的视图对象。 */
    private static BookingRecordVO toVO(BookingRecordEntity entity) {
        if (entity == null) {
            throw new BizException(BookingRecordErrorCode.RESOURCE_NOT_FOUND);
        }
        BookingRecordVO vo = new BookingRecordVO();
        BeanUtils.copyProperties(entity, vo);
        // 接口继续返回业务编码，不能依赖 BeanUtils 在枚举和字符串之间自动转换。
        vo.setBizType(entity.getBizType() == null ? null : entity.getBizType().getCode());
        return vo;
    }

    /** 将请求业务编码转换为实体枚举，不支持的编码按无效参数处理。 */
    private static BookingType requireBookingType(String value) {
        BookingType type = BookingType.of(required(value));
        if (type == null) {
            throw new BizException(BookingRecordErrorCode.INVALID_PARAMETER);
        }
        return type;
    }

    /** 查询并校验当前用户拥有的未删除记录。 */
    private BookingRecordEntity requireOwned(String userId, String bookingId) {
        BookingRecordEntity entity = mapper.owned(userId, bookingId);
        if (entity == null) {
            throw new BizException(BookingRecordErrorCode.RESOURCE_NOT_FOUND);
        }
        return entity;
    }

    /** 校验必填文本并去除首尾空白。 */
    private static String required(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new BizException(BookingRecordErrorCode.INVALID_PARAMETER);
        }
        return normalized;
    }

    /** 将空白文本归一化为空值。 */
    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 限制供应商状态文本长度，避免写入超出数据库列宽的响应内容。 */
    private static String safeExternalStatus(String value) {
        String normalized = normalize(value);
        return normalized == null ? null : normalized.substring(0, Math.min(64, normalized.length()));
    }

    /** 生成便于跨服务传递且全局冲突概率极低的内部预订单号。 */
    private static String newBookingId() {
        return "BK" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    }
}

