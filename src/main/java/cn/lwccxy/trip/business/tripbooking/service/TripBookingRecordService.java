package cn.lwccxy.trip.business.tripbooking.service;

import cn.lwccxy.trip.business.trip.domain.vo.TripApprovalRecordVO;
import cn.lwccxy.trip.business.trip.domain.vo.TripOrderVO;
import cn.lwccxy.trip.business.trip.service.TripApprovalRecordService;
import cn.lwccxy.trip.business.trip.service.TripOrderService;
import cn.lwccxy.trip.business.tripbooking.domain.entity.TripBookingRecordEntity;
import cn.lwccxy.trip.business.tripbooking.domain.vo.TripBookingRecordVO;
import cn.lwccxy.trip.business.tripbooking.mapper.TripBookingRecordMapper;
import cn.lwccxy.trip.common.exception.BizException;
import cn.lwccxy.trip.business.trip.enums.TripErrorCode;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** 管理差旅申请关联的供应商预订记录，并以数据库唯一键实施幂等保护。 */
@Service
public class TripBookingRecordService {

    private static final String PROVIDER_CODE = "TUNIU";
    private static final int MAX_PRODUCT_KEY_LENGTH = 256;
    private static final int MAX_PRODUCT_SUMMARY_LENGTH = 2000;

    private final TripBookingRecordMapper mapper;
    private final TripOrderService tripOrderService;
    private final TripApprovalRecordService approvalRecordService;

    /** 创建预订记录服务。 */
    public TripBookingRecordService(TripBookingRecordMapper mapper,
                                    TripOrderService tripOrderService,
                                    TripApprovalRecordService approvalRecordService) {
        this.mapper = mapper;
        this.tripOrderService = tripOrderService;
        this.approvalRecordService = approvalRecordService;
    }

    /** 查询当前用户申请单和产品类型下已有的供应商预订记录。 */
    public List<TripBookingRecordVO> list(String userId, String tripOrderId, String bizType) {
        String owner = required(userId, "当前会话缺少登录用户信息。请重新登录后重试。");
        String orderId = required(tripOrderId, "请提供关联的出差申请单 ID。");
        String type = normalizeBizType(bizType);
        requireApprovedTripOrder(owner, orderId);
        return mapper.listOwned(owner, orderId, type).stream()
                .map(TripBookingRecordService::toVO).toList();
    }

    /** 校验已审批申请并原子预占同一产品的幂等键，阻止并发重复下单。 */
    @Transactional
    public BookingPreparation prepare(String userId, String tripOrderId, String bizType,
                                      String productKey, String productSummary) {
        String owner = required(userId, "当前会话缺少登录用户信息。请重新登录后重试。");
        String orderId = required(tripOrderId, "请提供关联的出差申请单 ID。");
        String type = normalizeBizType(bizType);
        String normalizedProductKey = required(productKey, "缺少来自本轮搜索结果的产品标识。");
        String summary = required(productSummary, "请提供简短的产品摘要。");
        validateLength(normalizedProductKey, MAX_PRODUCT_KEY_LENGTH, "产品标识");
        validateLength(summary, MAX_PRODUCT_SUMMARY_LENGTH, "产品摘要");

        // 下单前在服务端再次校验申请单和审批记录，不能信任模型传入的状态或用户 ID。
        requireApprovedTripOrder(owner, orderId);
        String idempotencyKey = createIdempotencyKey(owner, orderId, type, normalizedProductKey);
        TripBookingRecordEntity existing = mapper.byIdempotencyKey(owner, idempotencyKey);
        if (existing != null) {
            return new BookingPreparation(toVO(existing), false,
                    "该申请单和产品已存在预订记录或未完成的下单尝试；为避免重复预订，本次不应调用供应商下单命令。");
        }

        TripBookingRecordEntity entity = new TripBookingRecordEntity();
        entity.setId(IdWorker.getIdStr());
        entity.setUserId(owner);
        entity.setTripOrderId(orderId);
        entity.setBizType(type);
        entity.setProductKey(normalizedProductKey);
        entity.setIdempotencyKey(idempotencyKey);
        entity.setProviderCode(PROVIDER_CODE);
        entity.setProductSummary(summary);
        entity.setStatus("PREPARING");
        try {
            // 数据库唯一键处理并发请求；只有插入成功的会话获得一次供应商写操作授权。
            if (mapper.insert(entity) != 1) {
                throw new IllegalStateException("预订幂等记录未能创建。");
            }
            return new BookingPreparation(toVO(mapper.owned(owner, entity.getId())), true,
                    "预订尝试已登记，可以继续执行一次供应商下单命令。");
        } catch (DuplicateKeyException exception) {
            TripBookingRecordEntity concurrent = mapper.byIdempotencyKey(owner, idempotencyKey);
            if (concurrent == null) {
                throw exception;
            }
            return new BookingPreparation(toVO(concurrent), false,
                    "相同产品已有并发预订尝试；为避免重复下单，本次停止。");
        }
    }

    /** 将已验证的供应商订单号写入对应预占记录，重复回调保持幂等。 */
    @Transactional
    public TripBookingRecordVO markCreated(String userId, String bookingRecordId,
                                           String providerOrderId, String providerStatus) {
        String owner = required(userId, "当前会话缺少登录用户信息。");
        String recordId = required(bookingRecordId, "缺少预订记录 ID。");
        String orderId = required(providerOrderId, "供应商未返回订单 ID，不能登记为已创建。");
        String status = safeStatus(providerStatus);
        TripBookingRecordEntity current = requireOwned(owner, recordId);
        if ("CREATED".equals(current.getStatus())) {
            if (orderId.equals(current.getProviderOrderId())) {
                return toVO(current);
            }
            throw new IllegalStateException("预订记录已关联其他供应商订单，不能覆盖。");
        }
        if (!"PREPARING".equals(current.getStatus())) {
            throw new IllegalStateException("预订记录状态不允许登记供应商订单。");
        }

        // 供应商订单号唯一约束与状态条件更新一起防止重复关联或迟到结果覆盖终态。
        if (mapper.markCreated(owner, recordId, orderId, status) != 1) {
            TripBookingRecordEntity latest = requireOwned(owner, recordId);
            if ("CREATED".equals(latest.getStatus()) && orderId.equals(latest.getProviderOrderId())) {
                return toVO(latest);
            }
            throw new IllegalStateException("预订结果状态已变化，无法确认本次持久化。");
        }
        return toVO(requireOwned(owner, recordId));
    }

    /** 标记供应商明确拒绝或无法确认的预订尝试，避免后续重复下单。 */
    @Transactional
    public TripBookingRecordVO markOutcome(String userId, String bookingRecordId,
                                           boolean providerFailed, String providerStatus) {
        String owner = required(userId, "当前会话缺少登录用户信息。");
        String recordId = required(bookingRecordId, "缺少预订记录 ID。");
        TripBookingRecordEntity current = requireOwned(owner, recordId);
        if (!"PREPARING".equals(current.getStatus())) {
            return toVO(current);
        }

        // 供应商明确拒绝记为 FAILED；响应或关联信息不确定时记为 UNKNOWN 并保留幂等阻断。
        String status = providerFailed ? "FAILED" : "UNKNOWN";
        if (mapper.markOutcome(owner, recordId, status, safeStatus(providerStatus)) != 1) {
            return toVO(requireOwned(owner, recordId));
        }
        return toVO(requireOwned(owner, recordId));
    }


    /** 在途牛确认取消后，按当前用户和供应商订单号更新差旅预占记录。 */
    @Transactional
    public boolean markCancelledByProviderOrder(String userId, String bizType,
                                                String providerOrderId, String providerStatus) {
        String owner = required(userId, "当前会话缺少登录用户信息。");
        String type = normalizeBizType(bizType);
        String orderId = required(providerOrderId, "缺少供应商订单 ID。");
        // 仅将已成功创建的预订改为取消，避免迟到响应覆盖失败或未知状态。
        return mapper.markCancelledByProviderOrder(owner, type, orderId, safeStatus(providerStatus)) == 1;
    }

    /** 确认指定出差申请和审批记录都属于用户且均处于 APPROVED 状态。 */
    private void requireApprovedTripOrder(String userId, String tripOrderId) {
        TripOrderVO tripOrder = tripOrderService.get(userId, tripOrderId).getData();
        if (tripOrder == null || !"APPROVED".equals(tripOrder.getStatus())
                || tripOrder.getApprovalId() == null || tripOrder.getApprovalId().isBlank()) {
            throw new IllegalStateException("当前没有审批通过且关联有效审批记录的差旅单，不可执行预订操作。");
        }
        TripApprovalRecordVO approval = approvalRecordService.get(userId, tripOrder.getApprovalId()).getData();
        if (approval == null || !"APPROVED".equals(approval.getStatus())
                || !tripOrderId.equals(approval.getOrderId())) {
            throw new IllegalStateException("当前差旅单审批状态未确认通过，不可执行预订操作。");
        }
    }

    /** 查询并校验当前认证用户拥有的预订记录。 */
    private TripBookingRecordEntity requireOwned(String userId, String recordId) {
        TripBookingRecordEntity entity = mapper.owned(userId, recordId);
        if (entity == null) {
            throw new IllegalStateException("未找到当前用户所属的预订记录。");
        }
        return entity;
    }

    /** 生成覆盖用户、申请单、产品类型和产品标识的稳定幂等键。 */
    private String createIdempotencyKey(String userId, String tripOrderId, String bizType, String productKey) {
        try {
            String source = String.join("|", userId, tripOrderId, bizType, productKey);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法初始化预订幂等校验。", exception);
        }
    }

    /** 将产品类型归一化为系统支持的预订分类。 */
    private String normalizeBizType(String bizType) {
        String type = required(bizType, "请提供产品类型：FLIGHT、HOTEL 或 TRAIN。").toUpperCase(Locale.ROOT);
        if (!List.of("FLIGHT", "HOTEL", "TRAIN").contains(type)) {
            throw new IllegalArgumentException("产品类型无效：仅支持 FLIGHT、HOTEL 或 TRAIN。");
        }
        return type;
    }

    /** 校验并归一化必填文本。 */
    private String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    /** 校验文本长度，拒绝可能截断产品标识或摘要的数据。 */
    private void validateLength(String value, int maxLength, String fieldName) {
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "过长，最多允许 " + maxLength + " 个字符。");
        }
    }

    /** 将供应商状态缩减为不含订单详情或个人信息的短文本。 */
    private String safeStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.substring(0, Math.min(64, normalized.length()));
    }

    /** 将数据库实体转成不暴露用户 ID 和幂等键的工具视图。 */
    private static TripBookingRecordVO toVO(TripBookingRecordEntity entity) {
        TripBookingRecordVO result = new TripBookingRecordVO();
        BeanUtils.copyProperties(entity, result);
        return result;
    }

    /** 预占操作返回值，created=false 表示必须阻断本次供应商下单。 */
    public record BookingPreparation(TripBookingRecordVO record, boolean created, String message) {
    }
}
