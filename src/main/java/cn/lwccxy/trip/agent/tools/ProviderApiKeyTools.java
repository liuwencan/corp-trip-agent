package cn.lwccxy.trip.agent.tools;

import cn.lwccxy.trip.agent.context.AgentRuntimeContext;
import cn.lwccxy.trip.business.apikey.domain.form.ProviderApiKeyQueryForm;
import cn.lwccxy.trip.business.apikey.domain.form.ProviderApiKeySaveForm;
import cn.lwccxy.trip.business.apikey.domain.vo.ProviderApiKeyVO;
import cn.lwccxy.trip.business.apikey.service.ProviderApiKeyService;
import cn.lwccxy.trip.common.response.PageResponse;
import cn.lwccxy.trip.common.response.Response;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 提供当前用户第三方旅行平台 API Key 的保存和配置状态查询工具。 */
@Component
public class ProviderApiKeyTools {

    private static final Logger logger = LoggerFactory.getLogger(ProviderApiKeyTools.class);
    private static final Pattern PROVIDER_CODE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final int MAX_API_KEY_LENGTH = 4096;

    private final ProviderApiKeyService providerApiKeyService;

    /** 创建 API Key 工具并注入凭证服务。 */
    public ProviderApiKeyTools(ProviderApiKeyService providerApiKeyService) {
        this.providerApiKeyService = providerApiKeyService;
    }

    /** 保存当前用户指定旅行平台的 API Key，已有同平台配置时更新原配置。 */
    @Tool(
            name = "save_provider_api_key",
            description = "保存当前登录用户的第三方旅行平台 API Key。providerCode 必填，例如 TUNIU；"
                    + "apiKey 必须是用户从第三方平台复制的完整密钥。已有同一平台配置时会覆盖旧密钥。"
                    + "如果用户还没有 TUNIU API Key，引导其前往途牛开放平台 https://open.tuniu.com/mcp/apikeys 配置并获取后再保存。"
                    + "工具不会在返回值中展示 API Key，也不要在回复中复述完整密钥。")
    public String saveProviderApiKey(
            AgentRuntimeContext sessionContext,
            @ToolParam(name = "providerCode", description = "第三方平台类型编码，例如 TUNIU") String providerCode,
            @ToolParam(name = "apiKey", description = "第三方平台 API Key 完整值") String apiKey,
            @ToolParam(name = "status", description = "可选状态：ACTIVE、INACTIVE、REVOKED；默认 ACTIVE", required = false)
            String status,
            @ToolParam(name = "remark", description = "可选备注", required = false) String remark) {

        logger.info("[saveProviderApiKey]执行");

        try {
            Long userId = requireUserId(sessionContext);
            String normalizedProviderCode = requireProviderCode(providerCode);
            String normalizedApiKey = requireApiKey(apiKey);
            ProviderApiKeySaveForm form = new ProviderApiKeySaveForm();
            form.setProviderCode(normalizedProviderCode);
            form.setApiKey(normalizedApiKey);
            form.setStatus(normalizeStatus(status));
            form.setRemark(normalize(remark));

            // 服务层负责加密和按用户、平台执行新增或更新，工具层只返回脱敏结果。
            Response<ProviderApiKeyVO> response = providerApiKeyService.saveOrUpdate(userId, form);
            if (!response.isSuccess() || response.getData() == null) {
                return "未能保存 " + normalizedProviderCode + " 平台的 API Key。原因："
                        + safeMessage(response.getMessage(), "凭证存储服务未返回有效结果")
                        + "。请告知用户保存失败，并建议检查输入后重试；不要复述 API Key。";
            }
            ProviderApiKeyVO saved = response.getData();
            return "已成功保存 " + saved.getProviderCode() + " 平台的 API Key，当前状态为 "
                    + statusText(saved.getStatus()) + "。密钥已安全保存，回复用户时不要展示或复述密钥。";
        } catch (IllegalArgumentException exception) {
            return "未能保存 API Key，原因：" + exception.getMessage()
                    + "。请根据原因向用户说明需要修正的平台类型或密钥内容，再由用户重新提供；不要复述已收到的密钥。";
        } catch (Exception exception) {
            // 日志只记录平台类型和异常，不记录用户提交的 API Key。
            logger.error("[TOOL][save_provider_api_key] 保存平台 API Key 失败, providerCode={}",
                    normalize(providerCode), exception);
            return "未能保存 " + displayProviderCode(providerCode)
                    + " 平台的 API Key，原因是服务暂时异常。请告知用户稍后重试，不要声称保存成功，也不要复述密钥。";
        }
    }

    /** 查询当前用户是否配置了一个或全部第三方旅行平台的 API Key。 */
    @Tool(
            name = "query_provider_api_key_status",
            description = "查询当前登录用户是否配置了第三方旅行平台 API Key。providerCode 可选；"
                    + "传入 TUNIU 等平台编码时只查询该平台，不传时返回所有平台配置状态。"
                    + "如果用户尚未配置 TUNIU，提示其前往途牛开放平台 https://open.tuniu.com/mcp/apikeys 配置并获取 API Key。"
                    + "结果只包含是否配置和状态，不会返回 API Key。")
    public String queryProviderApiKeyStatus(
            AgentRuntimeContext sessionContext,
            @ToolParam(name = "providerCode", description = "可选平台类型编码，例如 TUNIU；不传则查询全部平台", required = false)
            String providerCode) {

        logger.info("[queryProviderApiKeyStatus]执行");

        try {
            Long userId = requireUserId(sessionContext);
            String normalizedProviderCode = normalizeOptionalProviderCode(providerCode);
            ProviderApiKeyQueryForm form = new ProviderApiKeyQueryForm();
            form.setProviderCode(normalizedProviderCode);
            form.setStatus("ALL");
            form.setPageNo(1L);
            form.setPageSize(100L);

            PageResponse<ProviderApiKeyVO> response = providerApiKeyService.query(userId, form);
            List<ProviderApiKeyVO> records = response.getData() == null ? List.of() : response.getData();
            List<ProviderApiKeyVO> statuses = records;
            if (statuses.isEmpty()) {
                return normalizedProviderCode == null
                        ? "查询成功：当前用户尚未配置任何第三方平台 API Key。若要使用途牛服务，请先前往途牛开放平台 https://open.tuniu.com/mcp/apikeys 配置并获取 API Key，再提供给我保存。"
                        : "查询成功：当前用户尚未配置 " + normalizedProviderCode
                                + " 平台的 API Key。"
                                + ("TUNIU".equals(normalizedProviderCode)
                                ? "请引导用户前往途牛开放平台 https://open.tuniu.com/mcp/apikeys 配置并获取 API Key，再提供给我保存。"
                                : "可以请用户提供该平台的 API Key 后再调用保存工具。");
            }
            String details = statuses.stream()
                    .map(value -> value.getProviderCode() + "（" + statusText(value.getStatus()) + "）")
                    .reduce((left, right) -> left + "、" + right)
                    .orElse("");
            boolean tuniuConfigured = statuses.stream()
                    .anyMatch(value -> "TUNIU".equalsIgnoreCase(value.getProviderCode()));
            return "查询成功：已配置以下平台的 API Key：" + details + "。"
                    + (tuniuConfigured ? "" : "当前尚未配置 TUNIU。若要使用途牛服务，请引导用户前往途牛开放平台 https://open.tuniu.com/mcp/apikeys 配置并获取 API Key，再提供给我保存。")
                    + "这里只确认配置状态，没有读取或返回密钥内容。";
        } catch (IllegalArgumentException exception) {
            return "无法查询 API Key 配置，原因：" + exception.getMessage()
                    + "。请修正平台类型后重新查询；不传平台类型可以查询全部平台。";
        } catch (Exception exception) {
            logger.error("[TOOL][query_provider_api_key_status] 查询平台 API Key 状态失败, providerCode={}",
                    normalize(providerCode), exception);
            return "暂时无法查询 " + displayProviderCode(providerCode)
                    + " 平台的 API Key 配置，原因是服务暂时异常。请告知用户稍后重试，不要根据查询失败推断为未配置。";
        }
    }

    /** 校验并解析当前 Agent 会话中的用户 ID。 */
    private Long requireUserId(AgentRuntimeContext sessionContext) {
        if (sessionContext == null || sessionContext.getUserId() == null
                || sessionContext.getUserId().isBlank()) {
            throw new IllegalArgumentException("当前会话缺少登录用户信息，无法操作 API Key");
        }
        try {
            return Long.valueOf(sessionContext.getUserId().trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("当前会话用户 ID 无效");
        }
    }

    /** 校验平台类型编码并统一为大写。 */
    private String requireProviderCode(String providerCode) {
        String normalized = normalizeOptionalProviderCode(providerCode);
        if (normalized == null) {
            throw new IllegalArgumentException("providerCode 不能为空");
        }
        return normalized;
    }

    /** 校验 API Key 内容和长度，避免把空值交给持久化层。 */
    private String requireApiKey(String apiKey) {
        String normalized = normalize(apiKey);
        if (normalized == null) {
            throw new IllegalArgumentException("apiKey 不能为空");
        }
        if (normalized.length() > MAX_API_KEY_LENGTH) {
            throw new IllegalArgumentException("apiKey 长度不能超过 " + MAX_API_KEY_LENGTH + " 个字符");
        }
        return normalized;
    }

    /** 校验可选平台编码，空值表示查询全部平台。 */
    private String normalizeOptionalProviderCode(String providerCode) {
        String normalized = normalize(providerCode);
        if (normalized == null) {
            return null;
        }
        if (!PROVIDER_CODE_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("providerCode 只能包含字母、数字、下划线或短横线，长度不超过 32");
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    /** 统一保存状态，未提供时使用 ACTIVE。 */
    private String normalizeStatus(String status) {
        String normalized = normalize(status);
        if (normalized == null) {
            return "ACTIVE";
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!List.of("ACTIVE", "INACTIVE", "REVOKED").contains(normalized)) {
            throw new IllegalArgumentException("status 只能是 ACTIVE、INACTIVE 或 REVOKED");
        }
        return normalized;
    }

    /** 规范化可选文本参数。 */
    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 将持久化状态转换为便于 Agent 转述的中文说明。 */
    private String statusText(String status) {
        return switch (status == null ? "" : status.toUpperCase(Locale.ROOT)) {
            case "ACTIVE" -> "已启用";
            case "INACTIVE" -> "已停用";
            case "REVOKED" -> "已撤销";
            default -> "状态未知";
        };
    }

    /** 返回业务错误说明；没有可展示的明细时使用安全的默认提示。 */
    private String safeMessage(String message, String fallback) {
        return message == null || message.isBlank() ? fallback : message;
    }

    /** 为异常提示提供归一化的平台名称。 */
    private String displayProviderCode(String providerCode) {
        String normalized = normalize(providerCode);
        return normalized == null ? "指定" : normalized.toUpperCase(Locale.ROOT);
    }
}
