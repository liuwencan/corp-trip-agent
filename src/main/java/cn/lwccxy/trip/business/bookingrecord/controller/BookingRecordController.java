package cn.lwccxy.trip.business.bookingrecord.controller;

import cn.lwccxy.trip.business.bookingrecord.domain.form.BookingRecordQueryForm;
import cn.lwccxy.trip.business.bookingrecord.domain.form.BookingRecordSaveForm;
import cn.lwccxy.trip.business.bookingrecord.domain.vo.BookingRecordVO;
import cn.lwccxy.trip.business.bookingrecord.service.BookingRecordService;
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

/** 提供当前用户业务预订记录的 CRUD 接口。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/business/booking-records")
public class BookingRecordController {

    private final CurrentUser currentUser;
    private final BookingRecordService service;

    /** 创建当前用户的预订记录。 */
    @PostMapping
    public Response<BookingRecordVO> create(@Valid @RequestBody BookingRecordSaveForm form) {
        return service.create(ownerId(), form);
    }

    /** 分页查询当前用户的预订记录。 */
    @GetMapping
    public PageResponse<BookingRecordVO> list(@Valid BookingRecordQueryForm form) {
        return service.list(ownerId(), form);
    }

    /** 查询当前用户所属的单条预订记录。 */
    @GetMapping("/{bookingId}")
    public Response<BookingRecordVO> get(@PathVariable String bookingId) {
        return service.get(ownerId(), bookingId);
    }

    /** 部分更新当前用户所属的预订记录。 */
    @PutMapping("/{bookingId}")
    public Response<BookingRecordVO> update(@PathVariable String bookingId,
                                            @Valid @RequestBody BookingRecordSaveForm form) {
        return service.update(ownerId(), bookingId, form);
    }

    /** 逻辑删除当前用户所属的预订记录。 */
    @DeleteMapping("/{bookingId}")
    public Response<Void> delete(@PathVariable String bookingId) {
        return service.delete(ownerId(), bookingId);
    }

    /** 获取当前认证用户 ID 的字符串形式。 */
    private String ownerId() {
        return String.valueOf(currentUser.get().getId());
    }
}
