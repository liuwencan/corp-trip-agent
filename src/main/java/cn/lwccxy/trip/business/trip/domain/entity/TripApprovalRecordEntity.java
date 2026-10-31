package cn.lwccxy.trip.business.trip.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 出差申请审批记录数据库实体。 */
@Data
@TableName("trip_approval_record")
public class TripApprovalRecordEntity {

    /** 审批记录 ID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 申请人 ID。 */
    private String userId;

    /** 审批标题。 */
    private String title;

    /** 审批状态。 */
    private String status;

    /** 审批表单 JSON。 */
    private String approvalForm;

    /** 审批备注。 */
    private String remark;

    /** 提交时间。 */
    private LocalDateTime submitTime;

    /** 最后更新时间。 */
    private LocalDateTime updateTime;

    /** 关联出差申请单 ID。 */
    private String orderId;
}
