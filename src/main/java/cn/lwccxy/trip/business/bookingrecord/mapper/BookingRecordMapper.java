package cn.lwccxy.trip.business.bookingrecord.mapper;

import cn.lwccxy.trip.business.bookingrecord.domain.entity.BookingRecordEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 业务预订记录数据访问接口。 */
@Mapper
public interface BookingRecordMapper extends BaseMapper<BookingRecordEntity> {

    /** 按用户归属查询未删除的单条预订记录。 */
    @Select("SELECT * FROM booking_record WHERE user_id=#{userId} AND booking_id=#{bookingId} AND deleted=0")
    BookingRecordEntity owned(@Param("userId") String userId, @Param("bookingId") String bookingId);

    /** 按当前用户、平台、产品类型和供应商订单号将正式预订状态更新为已取消。 */
    @Update("UPDATE booking_record SET status='CANCELLED', external_status=#{externalStatus}, "
            + "updated_at=CURRENT_TIMESTAMP WHERE user_id=#{userId} AND platform=#{platform} "
            + "AND biz_type=#{bizType} AND external_order_no=#{externalOrderNo} AND deleted=0 "
            + "AND status NOT IN ('CANCELLED','COMPLETED','REFUNDED')")
    int markCancelledByExternalOrder(@Param("userId") String userId,
                                     @Param("platform") String platform,
                                     @Param("bizType") String bizType,
                                     @Param("externalOrderNo") String externalOrderNo,
                                     @Param("externalStatus") String externalStatus);

    /** 分页查询当前用户的预订记录。 */
    @Select("""
            <script>
            SELECT * FROM booking_record WHERE user_id=#{userId} AND deleted=0
            <if test="bookingId != null and bookingId != ''">AND booking_id=#{bookingId}</if>
            <if test="conversationId != null and conversationId != ''">AND conversation_id=#{conversationId}</if>
            <if test="travelOrderId != null and travelOrderId != ''">AND travel_order_id=#{travelOrderId}</if>
            <if test="bizType != null and bizType != ''">AND biz_type=#{bizType}</if>
            <if test="status != null and status != ''">AND status=#{status}</if>
            ORDER BY created_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<BookingRecordEntity> pageOwned(@Param("userId") String userId,
                                        @Param("bookingId") String bookingId,
                                        @Param("conversationId") String conversationId,
                                        @Param("travelOrderId") String travelOrderId,
                                        @Param("bizType") String bizType,
                                        @Param("status") String status,
                                        @Param("offset") long offset,
                                        @Param("limit") long limit);

    /** 统计当前用户符合筛选条件的未删除预订记录数。 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM booking_record WHERE user_id=#{userId} AND deleted=0
            <if test="bookingId != null and bookingId != ''">AND booking_id=#{bookingId}</if>
            <if test="conversationId != null and conversationId != ''">AND conversation_id=#{conversationId}</if>
            <if test="travelOrderId != null and travelOrderId != ''">AND travel_order_id=#{travelOrderId}</if>
            <if test="bizType != null and bizType != ''">AND biz_type=#{bizType}</if>
            <if test="status != null and status != ''">AND status=#{status}</if>
            </script>
            """)
    long countOwned(@Param("userId") String userId,
                    @Param("bookingId") String bookingId,
                    @Param("conversationId") String conversationId,
                    @Param("travelOrderId") String travelOrderId,
                    @Param("bizType") String bizType,
                    @Param("status") String status);
}
