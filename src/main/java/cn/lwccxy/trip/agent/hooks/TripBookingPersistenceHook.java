package cn.lwccxy.trip.agent.hooks;

import cn.lwccxy.trip.agent.sse.SseEmitterRegistry;
import cn.lwccxy.trip.business.bookingrecord.service.BookingRecordService;
import cn.lwccxy.trip.business.tripbooking.service.TripBookingRecordService;
import org.springframework.stereotype.Component;

/** 保留既有组件类名，兼容 BookingAgent 对预订持久化 Hook 的依赖。 */
@Component
public class TripBookingPersistenceHook extends BookingPersistenceHook {

    /** 创建无 SSE 推送的兼容 Hook，便于直接测试持久化行为。 */
    public TripBookingPersistenceHook(TripBookingRecordService tripBookingRecordService,
                                      BookingRecordService bookingRecordService) {
        super(tripBookingRecordService, bookingRecordService);
    }

    /** 创建兼容 Hook 并注入预订记录服务。 */
    @org.springframework.beans.factory.annotation.Autowired
    public TripBookingPersistenceHook(TripBookingRecordService tripBookingRecordService,
                                      BookingRecordService bookingRecordService,
                                      SseEmitterRegistry emitterRegistry) {
        super(tripBookingRecordService, bookingRecordService, emitterRegistry);
    }
}
