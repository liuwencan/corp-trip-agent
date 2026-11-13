package cn.lwccxy.trip.business.bookingrecord.service;

import cn.lwccxy.trip.business.apikey.service.ProviderApiKeyService;
import cn.lwccxy.trip.business.bookingrecord.domain.vo.BookingCancellationVO;
import cn.lwccxy.trip.business.bookingrecord.domain.vo.BookingRecordVO;
import cn.lwccxy.trip.business.bookingrecord.integration.TuniuBookingCancellationClient;
import cn.lwccxy.trip.common.exception.BizException;
import cn.lwccxy.trip.common.response.Response;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

/** 编排用户归属校验、供应商取消和本地状态同步，不在远程调用期间占用数据库事务。 */
@Service
public class BookingCancellationService {

    private final BookingRecordService bookingRecordService;
    private final ProviderApiKeyService apiKeyService;
    private final TuniuBookingCancellationClient cancellationClient;

    /** 创建取消服务，复用现有归属查询、凭证解密和本地事务更新能力。 */
    public BookingCancellationService(BookingRecordService bookingRecordService,
                                      ProviderApiKeyService apiKeyService,
                                      TuniuBookingCancellationClient cancellationClient) {
        this.bookingRecordService = bookingRecordService;
        this.apiKeyService = apiKeyService;
        this.cancellationClient = cancellationClient;
    }

    /**
     * 只取消当前用户在途牛的机票或火车订单；供应商明确成功后才将本地记录设为 CANCELLED。
     *
     * @param userId 会话用户 ID，不接受模型传入的其他用户身份
     * @param bookingId 内部预订单号
     * @return 包含可读提示的完整或部分处理结果，不返回原始异常或第三方响应
     */
    public Response<BookingCancellationVO> cancel(String userId, String bookingId) {
        if (userId == null || userId.isBlank()) {
            return result(false, "LOGIN_REQUIRED", "当前会话缺少登录用户信息，请重新登录后再取消预订。",
                    bookingId, null, null, false);
        }
        if (bookingId == null || bookingId.isBlank() || bookingId.trim().length() > 64) {
            return result(false, "INVALID_PARAMETER", "请先查询预订记录，再提供不超过 64 个字符的内部 bookingId。",
                    null, null, null, false);
        }
        String owner = userId.trim();
        String id = bookingId.trim();
        try {
            // 在获取密钥和访问供应商前先按用户过滤查询，其他用户记录与不存在记录统一不可见。
            Response<BookingRecordVO> response = bookingRecordService.get(owner, id);
            BookingRecordVO record = response == null || !response.isSuccess() ? null : response.getData();
            if (record == null || !owner.equals(record.getUserId()) || !id.equals(record.getBookingId())) {
                return result(false, "RESOURCE_NOT_FOUND", "未找到当前用户的这笔预订，请先查询本人预订记录并核对 bookingId。",
                        id, null, null, false);
            }
            if ("CANCELLED".equals(record.getStatus())) {
                return result(true, null, "该记录已标记为取消，本次没有重复请求途牛；供应商实际状态和退款情况请以平台查询为准。",
                        id, "CANCELLED", null, true);
            }
            if (!Set.of("CREATED", "PENDING_PAYMENT", "PAID", "CONFIRMED").contains(
                    record.getStatus() == null ? "" : record.getStatus())) {
                return result(false, "INVALID_STATUS", "这笔预订当前状态不支持直接取消；已完成或已退款订单请通过平台核实，不能当作本次取消成功。",
                        id, record.getStatus(), null, false);
            }
            String platform = record.getPlatform() == null ? "" : record.getPlatform().trim().toLowerCase(Locale.ROOT);
            if (!Set.of("tuniu-cli", "tuniu").contains(platform)
                    || !Set.of("FLIGHT", "TRAIN").contains(record.getBizType() == null ? "" : record.getBizType())) {
                return result(false, "CANCELLATION_UNSUPPORTED", "当前工具只支持途牛机票和火车票取消，这笔预订请使用对应平台的取消渠道。",
                        id, record.getStatus(), null, false);
            }
            if (record.getExternalOrderNo() == null || record.getExternalOrderNo().isBlank()) {
                return result(false, "EXTERNAL_ORDER_MISSING", "这笔记录缺少供应商订单号，无法发起取消；请先核实并补全真实订单号。",
                        id, record.getStatus(), null, false);
            }

            Long credentialOwner;
            try {
                credentialOwner = Long.valueOf(owner);
            } catch (NumberFormatException exception) {
                return result(false, "LOGIN_REQUIRED", "当前登录身份无法读取途牛凭证，请重新登录后重试。",
                        id, record.getStatus(), null, false);
            }
            // 凭证服务只查询当前用户的 ACTIVE 记录，并在服务内部解密，不再次手写解密逻辑。
            String apiKey;
            try {
                apiKey = apiKeyService.getActiveApiKey(credentialOwner, "TUNIU");
            } catch (Exception exception) {
                return result(false, "PROVIDER_KEY_UNAVAILABLE", "当前用户的途牛凭证读取或解密失败，请检查凭证配置，必要时联系管理员处理；本次未向途牛发起取消。",
                        id, record.getStatus(), null, false);
            }
            if (apiKey == null || apiKey.isBlank()) {
                return result(false, "PROVIDER_KEY_MISSING", "当前用户尚未配置可用的途牛 API Key，请先在服务商凭证设置中配置后重试。",
                        id, record.getStatus(), null, false);
            }
            var provider = cancellationClient.cancel(record.getBizType(), record.getExternalOrderNo().trim(), apiKey);
            if (provider.outcome() != TuniuBookingCancellationClient.Outcome.CONFIRMED) {
                return result(false, provider.errorCode(), providerMessage(provider.errorCode()), id, record.getStatus(),
                        provider.outcome() == TuniuBookingCancellationClient.Outcome.REJECTED ? false : null, false);
            }

            // 外部取消无法随数据库事务回滚，必须将本地同步失败作为部分成功返回，避免模型再次取消供应商订单。
            try {
                Response<Void> synced = bookingRecordService.cancel(owner, id);
                if (synced == null || !synced.isSuccess()) {
                    return localSyncFailed(id);
                }
            } catch (Exception exception) {
                return localSyncFailed(id);
            }
            return result(true, null, "途牛已确认取消订单，本地预订记录也已更新为已取消；退款进度和到账情况请以平台为准。",
                    id, "CANCELLED", true, true);
        } catch (BizException exception) {
            String code = exception.getErrorCode();
            return result(false, code, "RESOURCE_NOT_FOUND".equals(code)
                            ? "未找到当前用户的这笔预订，请先查询本人记录并核对 bookingId。"
                            : "预订记录校验未通过，请先查询订单详情并核对参数后再操作。",
                    id, null, null, false);
        } catch (Exception exception) {
            // 此处可能涉及解密异常，不能把异常文本或密钥写入工具结果及日志。
            return result(false, "CANCEL_FAILED", "取消流程暂时无法完成，请先查询途牛订单实际状态；未核实前不要重复发起取消。",
                    id, null, null, false);
        }
    }

    /** 为供应商已取消但本地同步失败的情况生成可恢复提示。 */
    private Response<BookingCancellationVO> localSyncFailed(String bookingId) {
        return result(false, "LOCAL_SYNC_FAILED", "途牛已确认取消，但本地记录更新失败；请联系管理员同步状态，不要再次向途牛发起取消。",
                bookingId, null, true, false);
    }

    /** 将 CLI 技术错误转换为模型可执行的下一步提示，不透传供应商或进程原文。 */
    private String providerMessage(String errorCode) {
        return switch (errorCode == null ? "" : errorCode) {
            case "PROVIDER_AUTH_FAILED" -> "途牛凭证验证失败，请在服务商凭证设置中检查或更新当前用户的 API Key 后再操作。";
            case "CLI_UNAVAILABLE", "CLI_CONFIGURATION_ERROR" -> "取消服务的途牛 CLI 环境或参数配置异常，请联系管理员检查；当前无法确认供应商取消成功。";
            case "PROVIDER_CANCEL_REJECTED" -> "途牛未同意本次取消，本地记录保持原状态；请到平台核实订单是否已支付、出票或不满足取消规则。";
            default -> "途牛取消结果尚未确认，可能超时或响应不完整；本地记录保持原状态，请先查询平台实际状态，不要自动重试取消。";
        };
    }

    /** 统一结果结构，同时记录供应商状态与本地同步状态，供模型区分未知和部分成功。 */
    private Response<BookingCancellationVO> result(boolean success, String code, String message,
                                                   String bookingId, String status,
                                                   Boolean providerCancelled, boolean localUpdated) {
        BookingCancellationVO data = new BookingCancellationVO();
        data.setBookingId(bookingId);
        data.setStatus(status);
        data.setProviderCancelled(providerCancelled);
        data.setLocalUpdated(localUpdated);
        Response<BookingCancellationVO> response = success ? Response.success(data) : Response.fail(code, message);
        response.setMessage(message);
        response.setData(data);
        return response;
    }
}
