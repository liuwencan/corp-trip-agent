package cn.lwccxy.trip.business.tripbooking.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 供应商预订记录数据库实体。 */
@Data
@TableName("trip_booking_record")
public class TripBookingRecordEntity {

    /** 预订记录 ID。 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 当前认证用户 ID。 */
    private String userId;

    /** 关联且已审批通过的出差申请单 ID。 */
    private String tripOrderId;

    /** 预订产品类型：FLIGHT、HOTEL 或 TRAIN。 */
    private String bizType;

    /** 来自本轮供应商搜索结果的稳定产品标识。 */
    private String productKey;

    /** 由申请单、产品类型和产品标识计算出的幂等键。 */
    private String idempotencyKey;

    /** 供应商代码。 */
    private String providerCode;

    /** 供应商订单 ID。 */
    private String providerOrderId;

    /** 不含乘客证件和联系方式的产品摘要。 */
    private String productSummary;

    /** 本地预订状态：PREPARING、CREATED、FAILED、UNKNOWN 或 CANCELLED。 */
    private String status;

    /** 供应商返回的非敏感状态文本。 */
    private String providerStatus;

    /** 创建时间。 */
    private LocalDateTime createdAt;

    /** 最后更新时间。 */
    private LocalDateTime updatedAt;
}
