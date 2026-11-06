package cn.lwccxy.trip.business.trip.service;

import cn.lwccxy.trip.business.trip.domain.entity.TripOrderEntity;
import cn.lwccxy.trip.business.trip.domain.entity.TripApprovalRecordEntity;
import cn.lwccxy.trip.business.trip.domain.form.TripOrderQueryForm;
import cn.lwccxy.trip.business.trip.domain.form.TripOrderSaveForm;
import cn.lwccxy.trip.business.trip.domain.vo.TripOrderVO;
import cn.lwccxy.trip.business.trip.domain.vo.TripApprovalRecordVO;
import cn.lwccxy.trip.business.trip.enums.TripErrorCode;
import cn.lwccxy.trip.business.trip.enums.TripOrderStatus;
import cn.lwccxy.trip.business.trip.mapper.TripOrderMapper;
import cn.lwccxy.trip.business.trip.mapper.TripApprovalRecordMapper;
import cn.lwccxy.trip.common.exception.BizException;
import cn.lwccxy.trip.common.response.PageResponse;
import cn.lwccxy.trip.common.response.Response;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.alibaba.fastjson2.JSON;

import java.util.List;
import java.util.UUID;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** 提供出差申请单的归属校验和 CRUD 能力。 */
@Service
public class TripOrderService {

    private final TripOrderMapper mapper;
    private final TripApprovalRecordMapper approvalRecordMapper;

    /** 创建出差申请单服务。 */
    public TripOrderService(TripOrderMapper mapper, TripApprovalRecordMapper approvalRecordMapper) {
        this.mapper = mapper;
        this.approvalRecordMapper = approvalRecordMapper;
    }

    /** 创建出差申请单并同步创建待审批记录，两个持久化动作必须原子提交。 */
    @Transactional
    public TripApplicationCreation createWithApproval(String userId, TripOrderSaveForm form) {
        String owner = required(userId);
        validateForm(form);
        validateApplicationFields(form);
        validateStatus(form.getStatus());
        if (normalize(form.getStatus()) != null && !TripOrderStatus.DRAFT.name().equals(normalize(form.getStatus()))) {
            throw new BizException(TripErrorCode.INVALID_PARAMETER);
        }
        if (normalize(form.getApprovalId()) != null) {
            throw new BizException(TripErrorCode.INVALID_PARAMETER);
        }
        String orderId = newId();
        String approvalId = newId();
        String destination = normalize(form.getDestination());
        String departureCity = normalize(form.getDepartureCity());
        String departureDate = normalize(form.getDepartureDate());
        String returnDate = normalize(form.getReturnDate());
        String purpose = normalize(form.getPurpose());

        TripOrderEntity order = new TripOrderEntity();
        order.setId(orderId);
        order.setUserId(owner);
        order.setDestination(destination);
        order.setDepartureCity(departureCity);
        order.setDepartureDate(departureDate);
        order.setReturnDate(returnDate);
        order.setPurpose(purpose);
        // 创建申请即进入审批队列，申请单状态和审批记录状态分别表达两个业务事实。
        order.setStatus(TripOrderStatus.SUBMITTED.name());
        order.setApprovalId(approvalId);

        TripApprovalRecordEntity approval = new TripApprovalRecordEntity();
        approval.setId(approvalId);
        approval.setUserId(owner);
        approval.setTitle("出差申请：" + destination);
        approval.setStatus(cn.lwccxy.trip.business.trip.enums.TripApprovalStatus.PENDING.name());
        approval.setApprovalForm(JSON.toJSONString(java.util.Map.of(
                "destination", destination,
                "departureCity", departureCity,
                "departureDate", departureDate,
                "returnDate", returnDate,
                "purpose", purpose)));
        approval.setRemark("由行程单管理工具创建，等待管理员审批");
        approval.setSubmitTime(java.time.LocalDateTime.now());
        approval.setOrderId(orderId);

        // 先写申请单，再写审批单；任一步失败都由事务回滚，避免出现无审批申请或悬空审批记录。
        changed(mapper.insert(order));
        changed(approvalRecordMapper.insert(approval));
        return new TripApplicationCreation(toVO(require(mapper.owned(owner, orderId))), toApprovalVO(approval));
    }

    /** 创建当前用户所属的出差申请单。 */
    @Transactional
    public Response<TripOrderVO> create(String userId, TripOrderSaveForm form) {
        // 所有申请创建入口共用原子流程，避免接口创建遗漏待审批记录。
        return Response.success(createWithApproval(userId, form).application());
    }

    /** 分页查询当前用户所属的出差申请单。 */
    public PageResponse<TripOrderVO> list(String userId, TripOrderQueryForm form) {
        String owner = required(userId);
        validateForm(form);
        validatePage(form.getPageNo(), form.getPageSize());
        String status = normalize(form.getStatus());
        validateStatus(status);
        String destination = normalize(form.getDestination());
        String departureCity = normalize(form.getDepartureCity());
        String departureDate = normalize(form.getDepartureDate());
        String returnDate = normalize(form.getReturnDate());
        long total = mapper.countOwned(owner, status, destination, departureCity, departureDate, returnDate);
        List<TripOrderVO> data = mapper.pageOwned(owner, status, destination, departureCity,
                        departureDate, returnDate,
                        PageResponse.getOffset(form.getPageNo(), form.getPageSize()), form.getPageSize())
                .stream().map(TripOrderService::toVO).toList();
        return PageResponse.success(data, form.getPageNo(), total, form.getPageSize());
    }

    /** 查询当前用户所属的单个出差申请单。 */
    public Response<TripOrderVO> get(String userId, String id) {
        return Response.success(toVO(require(mapper.owned(required(userId), required(id)))));
    }

    /** 查询当前用户与指定出差日期范围重叠的有效申请单。 */
    public List<TripOrderVO> findActiveOverlapping(String userId, String departureDate, String returnDate) {
        String owner = required(userId);
        LocalDate departure = parseDate(departureDate);
        LocalDate returning = parseDate(returnDate);
        if (departure.isAfter(returning)) {
            throw new BizException(TripErrorCode.INVALID_PARAMETER);
        }
        return mapper.findActiveOverlapping(owner, departure.toString(), returning.toString())
                .stream().map(TripOrderService::toVO).toList();
    }

    /** 更新当前用户所属的出差申请单。未提供的字段保留原值。 */
    @Transactional
    public Response<TripOrderVO> update(String userId, String id, TripOrderSaveForm form) {
        String owner = required(userId);
        String orderId = required(id);
        validateForm(form);
        TripOrderEntity old = require(mapper.owned(owner, orderId));
        String status = normalize(form.getStatus());
        validateStatus(status);

        // 以旧记录作为缺省值，支持工具只提交需要修改的字段。
        changed(mapper.updateOwned(owner, orderId,
                choose(form.getDestination(), old.getDestination()),
                choose(form.getDepartureCity(), old.getDepartureCity()),
                choose(form.getDepartureDate(), old.getDepartureDate()),
                choose(form.getReturnDate(), old.getReturnDate()),
                choose(form.getPurpose(), old.getPurpose()),
                status == null ? old.getStatus() : status,
                choose(form.getApprovalId(), old.getApprovalId())));
        return get(owner, orderId);
    }

    /** 删除当前用户所属的出差申请单。 */
    @Transactional
    public Response<Void> delete(String userId, String id) {
        String owner = required(userId);
        String orderId = required(id);
        require(mapper.owned(owner, orderId));
        changed(mapper.deleteOwned(owner, orderId));
        return Response.success();
    }

    /** 将数据库实体转换为接口返回对象。 */
    private static TripOrderVO toVO(TripOrderEntity entity) {
        TripOrderVO vo = new TripOrderVO();
        BeanUtils.copyProperties(entity, vo);
        return vo;
    }

    /** 将审批实体转换为工具和接口共用的审批视图。 */
    private static TripApprovalRecordVO toApprovalVO(TripApprovalRecordEntity entity) {
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

    /** 校验创建申请进入审批前必须完整具备的业务字段和日期范围。 */
    private static void validateApplicationFields(TripOrderSaveForm form) {
        if (normalize(form.getDestination()) == null || normalize(form.getDepartureCity()) == null
                || normalize(form.getDepartureDate()) == null || normalize(form.getReturnDate()) == null
                || normalize(form.getPurpose()) == null) {
            throw new BizException(TripErrorCode.INVALID_PARAMETER);
        }
        try {
            LocalDate departureDate = LocalDate.parse(form.getDepartureDate().trim());
            LocalDate returnDate = LocalDate.parse(form.getReturnDate().trim());
            if (departureDate.isAfter(returnDate)) {
                throw new BizException(TripErrorCode.INVALID_PARAMETER);
            }
        } catch (DateTimeParseException exception) {
            throw new BizException(TripErrorCode.INVALID_PARAMETER);
        }
    }

    /** 解析出差日期，非法值统一映射为业务参数错误。 */
    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(required(value));
        } catch (DateTimeParseException exception) {
            throw new BizException(TripErrorCode.INVALID_PARAMETER);
        }
    }

    /** 校验申请单状态只能使用表约定的枚举值。 */
    private static void validateStatus(String status) {
        if (status == null) {
            return;
        }
        try {
            TripOrderStatus.valueOf(status.trim());
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
            throw new IllegalStateException("Trip order persistence failed");
        }
    }

    /** 创建申请单及其审批记录的组合结果。 */
    public record TripApplicationCreation(TripOrderVO application, TripApprovalRecordVO approval) {
    }
}
