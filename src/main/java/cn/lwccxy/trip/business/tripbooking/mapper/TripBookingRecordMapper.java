package cn.lwccxy.trip.business.tripbooking.mapper;

import cn.lwccxy.trip.business.tripbooking.domain.entity.TripBookingRecordEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 供应商预订记录数据访问接口。 */
@Mapper
public interface TripBookingRecordMapper extends BaseMapper<TripBookingRecordEntity> {

    /** 按当前用户和幂等键查询已有预订或未完成尝试。 */
    @Select("SELECT * FROM trip_booking_record WHERE user_id=#{userId} AND idempotency_key=#{idempotencyKey}")
    TripBookingRecordEntity byIdempotencyKey(@Param("userId") String userId,
                                             @Param("idempotencyKey") String idempotencyKey);

    /** 查询当前用户某个已审批申请单下的预订记录。 */
    @Select("SELECT * FROM trip_booking_record WHERE user_id=#{userId} AND trip_order_id=#{tripOrderId} "
            + "AND biz_type=#{bizType} ORDER BY created_at DESC, id DESC LIMIT 100")
    List<TripBookingRecordEntity> listOwned(@Param("userId") String userId,
                                            @Param("tripOrderId") String tripOrderId,
                                            @Param("bizType") String bizType);

    /** 按当前用户查询单条预订记录。 */
    @Select("SELECT * FROM trip_booking_record WHERE id=#{id} AND user_id=#{userId}")
    TripBookingRecordEntity owned(@Param("userId") String userId, @Param("id") String id);

    /** 将预占记录原子更新为供应商已创建订单状态。 */
    @Update("UPDATE trip_booking_record SET status='CREATED', provider_order_id=#{providerOrderId}, "
            + "provider_status=#{providerStatus}, updated_at=CURRENT_TIMESTAMP "
            + "WHERE id=#{id} AND user_id=#{userId} AND status='PREPARING'")
    int markCreated(@Param("userId") String userId,
                    @Param("id") String id,
                    @Param("providerOrderId") String providerOrderId,
                    @Param("providerStatus") String providerStatus);

    /** 标记供应商拒绝本次请求或供应商结果无法确认的记录。 */
    @Update("UPDATE trip_booking_record SET status=#{status}, provider_status=#{providerStatus}, "
            + "updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND user_id=#{userId} AND status='PREPARING'")
    int markOutcome(@Param("userId") String userId,
                    @Param("id") String id,
                    @Param("status") String status,
                    @Param("providerStatus") String providerStatus);

    /** 按用户、产品类型和供应商订单号将成功预订记录更新为已取消。 */
    @Update("UPDATE trip_booking_record SET status='CANCELLED', provider_status=#{providerStatus}, "
            + "updated_at=CURRENT_TIMESTAMP WHERE user_id=#{userId} AND biz_type=#{bizType} "
            + "AND provider_code='TUNIU' AND provider_order_id=#{providerOrderId} AND status='CREATED'")
    int markCancelledByProviderOrder(@Param("userId") String userId,
                                    @Param("bizType") String bizType,
                                    @Param("providerOrderId") String providerOrderId,
                                    @Param("providerStatus") String providerStatus);
}
