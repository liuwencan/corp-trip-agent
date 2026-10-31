package cn.lwccxy.trip.business.trip.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.lwccxy.trip.business.trip.domain.form.TripApprovalAdminQueryForm;
import cn.lwccxy.trip.business.trip.domain.form.TripApprovalDecisionForm;
import cn.lwccxy.trip.business.trip.domain.vo.TripApprovalRecordVO;
import cn.lwccxy.trip.business.trip.service.TripApprovalRecordService;
import cn.lwccxy.trip.common.response.PageResponse;
import cn.lwccxy.trip.common.response.Response;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供管理员待审批申请查询和审批决定接口。 */
@Validated
@RestController
@RequiredArgsConstructor
@SaCheckRole("ADMIN")
@RequestMapping("/admin/trip/approval-records")
public class TripApprovalAdminController {

    private final TripApprovalRecordService service;

    /** 分页查询管理员可处理的待审批申请。 */
    @GetMapping("/pending")
    public PageResponse<TripApprovalRecordVO> pending(@Valid TripApprovalAdminQueryForm form) {
        return service.listPendingForAdmin(form);
    }

    /** 通过或驳回指定待审批申请，并同步更新申请单状态。 */
    @PostMapping("/{id}/decision")
    public Response<TripApprovalRecordVO> decide(@PathVariable String id,
                                                 @Valid @RequestBody TripApprovalDecisionForm form) {
        return service.decideByAdmin(id, form);
    }
}
