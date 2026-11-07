package cn.lwccxy.trip.business.trip.mapper;

import cn.lwccxy.trip.business.trip.domain.entity.TripOrderEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 出差申请单数据访问接口。 */
@Mapper
public interface TripOrderMapper extends BaseMapper<TripOrderEntity> {

    /** 查询当前用户所属的单个申请单。 */
    @Select("SELECT * FROM trip_order WHERE id=#{id} AND user_id=#{userId}")
    TripOrderEntity owned(@Param("userId") String userId, @Param("id") String id);

    /** 查询当前用户在指定日期范围内仍有效的重叠出差申请。 */
    @Select("SELECT * FROM trip_order WHERE user_id=#{userId} "
            + "AND status IN ('DRAFT', 'SUBMITTED', 'APPROVED') "
            + "AND departure_date <= #{returnDate} AND return_date >= #{departureDate} "
            + "ORDER BY departure_date ASC, id ASC")
    List<TripOrderEntity> findActiveOverlapping(@Param("userId") String userId,
                                                @Param("departureDate") String departureDate,
                                                @Param("returnDate") String returnDate);

    /** 查询当前用户在指定日期窗口内仍有效的申请，供检查同日和相邻日交通衔接。 */
    @Select("SELECT * FROM trip_order WHERE user_id=#{userId} "
            + "AND status IN ('DRAFT', 'SUBMITTED', 'APPROVED') "
            + "AND departure_date <= #{windowEnd} AND return_date >= #{windowStart} "
            + "ORDER BY departure_date ASC, id ASC")
    List<TripOrderEntity> findActiveInDateWindow(@Param("userId") String userId,
                                                @Param("windowStart") String windowStart,
                                                @Param("windowEnd") String windowEnd);

    /** 按当前用户锁定单个申请单，供更新时保持归属校验和数据读取一致。 */
    @Select("SELECT * FROM trip_order WHERE id=#{id} AND user_id=#{userId} FOR UPDATE")
    TripOrderEntity lockOwned(@Param("userId") String userId, @Param("id") String id);

    /** 分页查询当前用户所属的申请单。 */
    @Select("""
            <script>
            SELECT * FROM trip_order
            WHERE user_id=#{userId}
            <if test="status != null and status != ''">AND status=#{status}</if>
            <if test="destination != null and destination != ''">AND destination LIKE CONCAT('%', #{destination}, '%')</if>
            <if test="departureCity != null and departureCity != ''">AND departure_city LIKE CONCAT('%', #{departureCity}, '%')</if>
            <if test="departureDate != null and departureDate != ''">AND departure_date=#{departureDate}</if>
            <if test="returnDate != null and returnDate != ''">AND return_date=#{returnDate}</if>
            ORDER BY updated_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<TripOrderEntity> pageOwned(@Param("userId") String userId,
                                    @Param("status") String status,
                                    @Param("destination") String destination,
                                    @Param("departureCity") String departureCity,
                                    @Param("departureDate") String departureDate,
                                    @Param("returnDate") String returnDate,
                                    @Param("offset") long offset,
                                    @Param("limit") long limit);

    /** 统计当前用户符合条件的申请单数量。 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM trip_order
            WHERE user_id=#{userId}
            <if test="status != null and status != ''">AND status=#{status}</if>
            <if test="destination != null and destination != ''">AND destination LIKE CONCAT('%', #{destination}, '%')</if>
            <if test="departureCity != null and departureCity != ''">AND departure_city LIKE CONCAT('%', #{departureCity}, '%')</if>
            <if test="departureDate != null and departureDate != ''">AND departure_date=#{departureDate}</if>
            <if test="returnDate != null and returnDate != ''">AND return_date=#{returnDate}</if>
            </script>
            """)
    long countOwned(@Param("userId") String userId,
                    @Param("status") String status,
                    @Param("destination") String destination,
                    @Param("departureCity") String departureCity,
                    @Param("departureDate") String departureDate,
                    @Param("returnDate") String returnDate);

    /** 更新当前用户所属申请单的全部业务字段。 */
    @Update("""
            UPDATE trip_order
            SET destination=#{destination}, departure_city=#{departureCity},
                departure_date=#{departureDate}, return_date=#{returnDate},
                purpose=#{purpose}, status=#{status}, approval_id=#{approvalId},
                updated_at=CURRENT_TIMESTAMP
            WHERE id=#{id} AND user_id=#{userId}
            """)
    int updateOwned(@Param("userId") String userId,
                    @Param("id") String id,
                    @Param("destination") String destination,
                    @Param("departureCity") String departureCity,
                    @Param("departureDate") String departureDate,
                    @Param("returnDate") String returnDate,
                    @Param("purpose") String purpose,
                    @Param("status") String status,
                    @Param("approvalId") String approvalId);

    /** 更新当前用户所属申请单的关联审批记录 ID。 */
    @Update("UPDATE trip_order SET approval_id=#{approvalId}, updated_at=CURRENT_TIMESTAMP "
            + "WHERE id=#{id} AND user_id=#{userId}")
    int updateApprovalId(@Param("userId") String userId,
                         @Param("id") String id,
                         @Param("approvalId") String approvalId);

    /** 根据审批决定同步更新申请单状态，仅允许更新已提交申请。 */
    @Update("UPDATE trip_order SET status=#{status}, updated_at=CURRENT_TIMESTAMP "
            + "WHERE id=#{id} AND status='SUBMITTED'")
    int updateStatusForApproval(@Param("id") String id, @Param("status") String status);

    /** 删除当前用户所属的申请单。 */
    @Delete("DELETE FROM trip_order WHERE id=#{id} AND user_id=#{userId}")
    int deleteOwned(@Param("userId") String userId, @Param("id") String id);
}
