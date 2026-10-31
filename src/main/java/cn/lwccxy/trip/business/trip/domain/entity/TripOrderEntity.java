package cn.lwccxy.trip.business.trip.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 出差申请单数据库实体。 */
@Data
@TableName("trip_order")
public class TripOrderEntity {

    /** 申请单 ID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 申请人 ID。 */
    private String userId;

    /** 目的地。 */
    private String destination;

    /** 出发城市。 */
    private String departureCity;

    /** 出发日期。 */
    private String departureDate;

    /** 返回日期。 */
    private String returnDate;

    /** 出差事由。 */
    private String purpose;

    /** 申请单状态。 */
    private String status;

    /** 关联审批单 ID。 */
    private String approvalId;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 最后修改时间。 */
    private LocalDateTime updatedAt;
}
