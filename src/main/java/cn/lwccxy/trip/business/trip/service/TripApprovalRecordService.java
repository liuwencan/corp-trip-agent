package cn.lwccxy.trip.business.trip.service;

import cn.lwccxy.trip.business.trip.domain.entity.TripApprovalRecordEntity;
import cn.lwccxy.trip.business.trip.domain.entity.TripOrderEntity;
import cn.lwccxy.trip.business.trip.domain.form.TripApprovalRecordQueryForm;
import cn.lwccxy.trip.business.trip.domain.form.TripApprovalRecordSaveForm;
import cn.lwccxy.trip.business.trip.domain.form.TripApprovalAdminQueryForm;
import cn.lwccxy.trip.business.trip.domain.form.TripApprovalDecisionForm;
import cn.lwccxy.trip.business.trip.domain.vo.TripApprovalRecordVO;
import cn.lwccxy.trip.business.trip.enums.TripApprovalStatus;
import cn.lwccxy.trip.business.trip.enums.TripErrorCode;
import cn.lwccxy.trip.business.trip.enums.TripOrderStatus;
import cn.lwccxy.trip.business.trip.mapper.TripApprovalRecordMapper;
import cn.lwccxy.trip.business.trip.mapper.TripOrderMapper;
import cn.lwccxy.trip.common.exception.BizException;
import cn.lwccxy.trip.common.response.PageResponse;
import cn.lwccxy.trip.common.response.Response;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 提供出差申请审批记录的归属校验和 CRUD 能力。 */
@Service
public class TripApprovalRecordService {

    private final TripApprovalRecordMapper mapper;
    private final TripOrderMapper tripOrderMapper;

    /** 创建审批记录服务。 */
    public TripApprovalRecordService(TripApprovalRecordMapper mapper, TripOrderMapper tripOrderMapper) {
        this.mapper = mapper;
        this.tripOrderMapper = tripOrderMapper;
    }

    /** 创建当前用户所属的审批记录，并校验关联申请单归属。 */
    @Transactional
    public Response<TripApprovalRecordVO> create(String userId, TripApprovalRecordSaveForm form) {
        String owner = required(userId);
        validateForm(form);
        validateStatus(form.getStatus());
        if (normalize(form.getStatus()) != null
                && !TripApprovalStatus.PENDING.name().equals(normalize(form.getStatus()))) {
            throw new BizException(TripErrorCode.INVALID_PARAMETER);
        }
        String orderId = normalize(form.getOrderId());
        validateOrderOwnership(owner, orderId);
        TripApprovalRecordEntity entity = new TripApprovalRecordEntity();
        entity.setId(newId());
        entity.setUserId(owner);
        entity.setTitle(normalize(form.getTitle()));
        entity.setStatus(normalize(form.getStatus()) == null
                ? TripApprovalStatus.PENDING.name() : form.getStatus().trim());
        entity.setApprovalForm(normalize(form.getApprovalForm()));
        entity.setRemark(normalize(form.getRemark()));
        entity.setSubmitTime(LocalDateTime.now());
        entity.setOrderId(orderId);

        // 审批记录创建时保存提交时间，更新时间交由数据库维护。
        changed(mapper.insert(entity));
        if (orderId != null) {
            // 双向关联中，申请单保存当前审批记录 ID，保证后续查询可从任一对象定位关联记录。
            changed(tripOrderMapper.updateApprovalId(owner, orderId, entity.getId()));
        }
        return Response.success(toVO(require(mapper.owned(owner, entity.getId()))));
    }

    /** 分页查询当前用户所属的审批记录。 */
    public PageResponse<TripApprovalRecordVO> list(String userId, TripApprovalRecordQueryForm form) {
        String owner = required(userId);
        validateForm(form);
        validatePage(form.getPageNo(), form.getPageSize());
        String status = normalize(form.getStatus());
        validateStatus(status);
        String orderId = normalize(form.getOrderId());
        long total = mapper.countOwned(owner, status, orderId);
        List<TripApprovalRecordVO> data = mapper.pageOwned(owner, status, orderId,
                        PageResponse.getOffset(form.getPageNo(), form.getPageSize()), form.getPageSize())
                .stream().map(TripApprovalRecordService::toVO).toList();
        return PageResponse.success(data, form.getPageNo(), total, form.getPageSize());
    }

    /** 查询当前用户所属的单条审批记录。 */
    public Response<TripApprovalRecordVO> get(String userId, String id) {
        return Response.success(toVO(require(mapper.owned(required(userId), required(id)))));
    }

    /** 分页查询管理员当前可处理的待审批申请。 */
    public PageResponse<TripApprovalRecordVO> listPendingForAdmin(TripApprovalAdminQueryForm form) {
        validateForm(form);
        validatePage(form.getPageNo(), form.getPageSize());
        long total = mapper.countPendingForAdmin();
        List<TripApprovalRecordVO> data = mapper.pagePendingForAdmin(
                        PageResponse.getOffset(form.getPageNo(), form.getPageSize()), form.getPageSize())
                .stream().map(TripApprovalRecordService::toVO).toList();
        return PageResponse.success(data, form.getPageNo(), total, form.getPageSize());
    }

    /** 管理员审批待处理记录，并将申请单状态与审批结论原子同步。 */
    @Transactional
    public Response<TripApprovalRecordVO> decideByAdmin(String recordId, TripApprovalDecisionForm form) {
        String id = required(recordId);
        validateForm(form);
        String decision = normalize(form.getDecision());
        if (!"APPROVED".equals(decision) && !"REJECTED".equals(decision)) {
            throw new BizException(TripErrorCode.INVALID_PARAMETER);
        }
        String remark = normalize(form.getRemark());
        if ("REJECTED".equals(decision) && remark == null) {
            throw new BizException(TripErrorCode.INVALID_PARAMETER);
        }

        // 锁定待审批记录后再更新，防止两个管理员同时处理同一申请。
        TripApprovalRecordEntity record = require(mapper.lockById(id));
        if (!TripApprovalStatus.PENDING.name().equals(record.getStatus())) {
            throw new BizException(TripErrorCode.INVALID_PARAMETER);
        }
        changed(mapper.decidePending(id, decision, remark));
        if (record.getOrderId() == null) {
            throw new BizException(TripErrorCode.ORDER_RELATION_INVALID);
        }
        // 申请单只允许从 SUBMITTED 进入审批结论状态，避免覆盖已被其他流程改变的状态。
        String orderStatus = "APPROVED".equals(decision)
                ? TripOrderStatus.APPROVED.name() : TripOrderStatus.REJECTED.name();
        if (tripOrderMapper.updateStatusForApproval(record.getOrderId(), orderStatus) != 1) {
            throw new BizException(TripErrorCode.ORDER_RELATION_INVALID);
        }
        return Response.success(toVO(require(mapper.owned(record.getUserId(), id))));
    }

    /** 更新当前用户所属审批记录，未提供的字段保留原值。 */
    @Transactional
    public Response<TripApprovalRecordVO> update(String userId, String id, TripApprovalRecordSaveForm form) {
        String owner = required(userId);
        String recordId = required(id);
        validateForm(form);
        TripApprovalRecordEntity old = require(mapper.owned(owner, recordId));
        validateStatus(form.getStatus());
        if (form.getStatus() != null && !form.getStatus().isBlank()
                && !form.getStatus().trim().equals(old.getStatus())) {
            // 申请人只能补充审批信息，审批状态必须由管理员决定，防止自审批。
            throw new BizException(TripErrorCode.INVALID_PARAMETER);
        }
        String orderId = choose(form.getOrderId(), old.getOrderId());
        validateOrderOwnership(owner, orderId);

        // 以旧记录作为缺省值，避免部分更新清空审批表单或关联申请单。
        changed(mapper.updateOwned(owner, recordId,
                choose(form.getTitle(), old.getTitle()),
                choose(form.getStatus(), old.getStatus()),
                choose(form.getApprovalForm(), old.getApprovalForm()),
                choose(form.getRemark(), old.getRemark()),
                old.getSubmitTime(), orderId));
        if (!Objects.equals(old.getOrderId(), orderId)) {
            // 改绑审批记录时清理旧申请单指针，再把新申请单指向当前审批记录。
            if (old.getOrderId() != null) {
                TripOrderEntity oldOrder = tripOrderMapper.owned(owner, old.getOrderId());
                if (oldOrder != null && Objects.equals(oldOrder.getApprovalId(), recordId)) {
                    changed(tripOrderMapper.updateApprovalId(owner, old.getOrderId(), null));
                }
            }
            if (orderId != null) {
                changed(tripOrderMapper.updateApprovalId(owner, orderId, recordId));
            }
        }
        return get(owner, recordId);
    }

    /** 删除当前用户所属的审批记录。 */
    @Transactional
    public Response<Void> delete(String userId, String id) {
        String owner = required(userId);
        String recordId = required(id);
        TripApprovalRecordEntity record = require(mapper.owned(owner, recordId));
        changed(mapper.deleteOwned(owner, recordId));
        if (record.getOrderId() != null) {
            TripOrderEntity order = tripOrderMapper.owned(owner, record.getOrderId());
            if (order != null && Objects.equals(order.getApprovalId(), recordId)) {
                // 只有申请单当前仍指向被删除记录时才清空指针，避免覆盖新的审批关联。
                changed(tripOrderMapper.updateApprovalId(owner, record.getOrderId(), null));
            }
        }
        return Response.success();
    }

    /** 校验关联申请单属于当前用户。 */
    private void validateOrderOwnership(String userId, String orderId) {
        if (orderId != null && tripOrderMapper.owned(userId, orderId) == null) {
            throw new BizException(TripErrorCode.ORDER_RELATION_INVALID);
        }
    }

    /** 将数据库实体转换为接口返回对象。 */
    private static TripApprovalRecordVO toVO(TripApprovalRecordEntity entity) {
        TripApprovalRecordVO vo = new TripApprovalRecordVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    /** 返回非空白文本，便于查询条件和可选字段统一处理。 */
    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 返回请求值或旧值，支持部分更新。 */
    private static String choose(String current, String previous) {
        return current == null ? previous : current.trim();
    }

    /** 校验分页参数范围。 */
    private static void validatePage(Long pageNo, Long pageSize) {
        if (pageNo == null || pageSize == null || pageNo < 1 || pageNo > Integer.MAX_VALUE
                || pageSize < 1 || pageSize > 100) {
            throw new BizException(TripErrorCode.INVALID_PARAMETER);
        }
    }

    /** 校验服务层直接调用时传入的请求对象。 */
    private static void validateForm(Object form) {
        if (form == null) {
            throw new BizException(TripErrorCode.INVALID_PARAMETER);
        }
    }

    /** 校验审批记录状态只能使用表约定的枚举值。 */
    private static void validateStatus(String status) {
        if (status == null) {
            return;
        }
        try {
            TripApprovalStatus.valueOf(status.trim());
        } catch (IllegalArgumentException exception) {
            throw new BizException(TripErrorCode.INVALID_PARAMETER);
        }
    }

    /** 校验必填标识并返回归一化值。 */
    private static String required(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new BizException(TripErrorCode.INVALID_PARAMETER);
        }
        return normalized;
    }

    /** 生成表结构要求的字符串主键。 */
    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 将空查询结果转换为统一的资源不存在异常。 */
    private static <T> T require(T value) {
        if (value == null) {
            throw new BizException(TripErrorCode.RESOURCE_NOT_FOUND);
        }
        return value;
    }

    /** 校验持久化操作只影响一条记录。 */
    private static void changed(int rows) {
        if (rows != 1) {
            throw new IllegalStateException("Trip approval persistence failed");
        }
    }
}
