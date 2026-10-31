package cn.lwccxy.trip.business.trip.mapper;

import cn.lwccxy.trip.business.trip.domain.entity.TripApprovalRecordEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 出差申请审批记录数据访问接口。 */
@Mapper
public interface TripApprovalRecordMapper extends BaseMapper<TripApprovalRecordEntity> {

    /** 查询当前用户所属的单条审批记录。 */
    @Select("SELECT * FROM trip_approval_record WHERE id=#{id} AND user_id=#{userId}")
    TripApprovalRecordEntity owned(@Param("userId") String userId, @Param("id") String id);

    /** 锁定指定审批记录，管理员处理时用于保证状态检查和状态变更串行执行。 */
    @Select("SELECT * FROM trip_approval_record WHERE id=#{id} FOR UPDATE")
    TripApprovalRecordEntity lockById(@Param("id") String id);

    /** 分页查询管理员可见的待审批记录。 */
    @Select("""
            SELECT * FROM trip_approval_record
            WHERE status='PENDING'
            ORDER BY submit_time ASC, id ASC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<TripApprovalRecordEntity> pagePendingForAdmin(@Param("offset") long offset,
                                                       @Param("limit") long limit);

    /** 统计管理员待审批记录数量。 */
    @Select("SELECT COUNT(*) FROM trip_approval_record WHERE status='PENDING'")
    long countPendingForAdmin();

    /** 仅允许管理员将待审批记录更新为审批终态。 */
    @Update("UPDATE trip_approval_record SET status=#{status}, remark=#{remark}, update_time=CURRENT_TIMESTAMP "
            + "WHERE id=#{id} AND status='PENDING'")
    int decidePending(@Param("id") String id,
                      @Param("status") String status,
                      @Param("remark") String remark);

    /** 分页查询当前用户所属的审批记录。 */
    @Select("""
            <script>
            SELECT * FROM trip_approval_record
            WHERE user_id=#{userId}
            <if test="status != null and status != ''">AND status=#{status}</if>
            <if test="orderId != null and orderId != ''">AND order_id=#{orderId}</if>
            ORDER BY submit_time DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<TripApprovalRecordEntity> pageOwned(@Param("userId") String userId,
                                             @Param("status") String status,
                                             @Param("orderId") String orderId,
                                             @Param("offset") long offset,
                                             @Param("limit") long limit);

    /** 统计当前用户符合条件的审批记录数量。 */
    @Select("""
            <script>
            SELECT COUNT(*) FROM trip_approval_record
            WHERE user_id=#{userId}
            <if test="status != null and status != ''">AND status=#{status}</if>
            <if test="orderId != null and orderId != ''">AND order_id=#{orderId}</if>
            </script>
            """)
    long countOwned(@Param("userId") String userId,
                    @Param("status") String status,
                    @Param("orderId") String orderId);

    /** 更新当前用户所属审批记录的业务字段。 */
    @Update("""
            UPDATE trip_approval_record
            SET title=#{title}, status=#{status}, approval_form=#{approvalForm},
                remark=#{remark}, submit_time=#{submitTime}, update_time=CURRENT_TIMESTAMP,
                order_id=#{orderId}
            WHERE id=#{id} AND user_id=#{userId}
            """)
    int updateOwned(@Param("userId") String userId,
                    @Param("id") String id,
                    @Param("title") String title,
                    @Param("status") String status,
                    @Param("approvalForm") String approvalForm,
                    @Param("remark") String remark,
                    @Param("submitTime") java.time.LocalDateTime submitTime,
                    @Param("orderId") String orderId);

    /** 删除当前用户所属的审批记录。 */
    @Delete("DELETE FROM trip_approval_record WHERE id=#{id} AND user_id=#{userId}")
    int deleteOwned(@Param("userId") String userId, @Param("id") String id);
}
