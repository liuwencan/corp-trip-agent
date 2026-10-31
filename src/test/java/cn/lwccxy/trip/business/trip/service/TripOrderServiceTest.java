package cn.lwccxy.trip.business.trip.service;

import cn.lwccxy.trip.business.trip.domain.entity.TripApprovalRecordEntity;
import cn.lwccxy.trip.business.trip.domain.entity.TripOrderEntity;
import cn.lwccxy.trip.business.trip.domain.form.TripOrderSaveForm;
import cn.lwccxy.trip.business.trip.enums.TripApprovalStatus;
import cn.lwccxy.trip.business.trip.enums.TripOrderStatus;
import cn.lwccxy.trip.business.trip.mapper.TripApprovalRecordMapper;
import cn.lwccxy.trip.business.trip.mapper.TripOrderMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

import java.util.concurrent.atomic.AtomicReference;

/** 出差申请与审批记录原子创建流程测试。 */
@ExtendWith(MockitoExtension.class)
class TripOrderServiceTest {

    @Mock
    private TripOrderMapper tripOrderMapper;

    @Mock
    private TripApprovalRecordMapper approvalRecordMapper;

    @InjectMocks
    private TripOrderService tripOrderService;

    /** 创建申请时同时建立关联的已提交申请和待审批记录。 */
    @Test
    void createWithApprovalCreatesLinkedSubmittedOrderAndPendingApproval() {
        TripOrderSaveForm form = new TripOrderSaveForm();
        form.setDestination("深圳");
        form.setDepartureCity("上海");
        form.setDepartureDate("2026-11-03");
        form.setReturnDate("2026-11-05");
        form.setPurpose("客户拜访");
        AtomicReference<TripOrderEntity> persistedOrder = new AtomicReference<>();
        doAnswer(invocation -> {
            persistedOrder.set(invocation.getArgument(0));
            return 1;
        }).when(tripOrderMapper).insert(any(TripOrderEntity.class));
        when(approvalRecordMapper.insert(any(TripApprovalRecordEntity.class))).thenReturn(1);
        when(tripOrderMapper.owned(anyString(), anyString())).thenAnswer(invocation -> persistedOrder.get());

        TripOrderService.TripApplicationCreation result = tripOrderService.createWithApproval("user-id", form);

        ArgumentCaptor<TripOrderEntity> orderCaptor = ArgumentCaptor.forClass(TripOrderEntity.class);
        ArgumentCaptor<TripApprovalRecordEntity> approvalCaptor = ArgumentCaptor.forClass(TripApprovalRecordEntity.class);
        verify(tripOrderMapper).insert(orderCaptor.capture());
        verify(approvalRecordMapper).insert(approvalCaptor.capture());
        assertEquals(TripOrderStatus.SUBMITTED.name(), orderCaptor.getValue().getStatus());
        assertEquals(orderCaptor.getValue().getId(), approvalCaptor.getValue().getOrderId());
        assertEquals(orderCaptor.getValue().getApprovalId(), approvalCaptor.getValue().getId());
        assertEquals(TripApprovalStatus.PENDING.name(), approvalCaptor.getValue().getStatus());
        assertEquals(orderCaptor.getValue().getApprovalId(), result.application().getApprovalId());
        assertNotNull(result.application());
        assertNotNull(result.approval());
    }
}
