package cn.lwccxy.trip.business.trip.controller;

import cn.lwccxy.trip.business.trip.domain.form.TripOrderQueryForm;
import cn.lwccxy.trip.business.trip.domain.form.TripOrderSaveForm;
import cn.lwccxy.trip.business.trip.domain.vo.TripOrderVO;
import cn.lwccxy.trip.business.trip.service.TripOrderService;
import cn.lwccxy.trip.common.auth.CurrentUser;
import cn.lwccxy.trip.common.response.PageResponse;
import cn.lwccxy.trip.common.response.Response;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供当前用户出差申请单的 CRUD 接口。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/trip/orders")
public class TripOrderController {

    private final CurrentUser currentUser;
    private final TripOrderService service;

    /** 创建当前用户所属的出差申请单。 */
    @PostMapping
    public Response<TripOrderVO> create(@Valid @RequestBody TripOrderSaveForm form) {
        return service.create(ownerId(), form);
    }

    /** 分页查询当前用户所属的出差申请单。 */
    @GetMapping
    public PageResponse<TripOrderVO> list(@Valid TripOrderQueryForm form) {
        return service.list(ownerId(), form);
    }

    /** 查询当前用户所属的单个出差申请单。 */
    @GetMapping("/{id}")
    public Response<TripOrderVO> get(@PathVariable String id) {
        return service.get(ownerId(), id);
    }

    /** 更新当前用户所属的出差申请单。 */
    @PutMapping("/{id}")
    public Response<TripOrderVO> update(@PathVariable String id,
                                        @Valid @RequestBody TripOrderSaveForm form) {
        return service.update(ownerId(), id, form);
    }

    /** 删除当前用户所属的出差申请单。 */
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable String id) {
        return service.delete(ownerId(), id);
    }

    /** 获取当前认证用户 ID 的字符串形式，以匹配业务表字段类型。 */
    private String ownerId() {
        return String.valueOf(currentUser.get().getId());
    }
}
