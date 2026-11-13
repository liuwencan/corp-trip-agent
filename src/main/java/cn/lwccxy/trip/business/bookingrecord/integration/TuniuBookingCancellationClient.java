package cn.lwccxy.trip.business.bookingrecord.integration;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 使用隔离子进程执行途牛机票、火车票取消命令，并核实业务响应。 */
@Component
public class TuniuBookingCancellationClient {

    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;
    private static final int PROCESS_TIMEOUT_SECONDS = 60;

    /**
     * 执行一次取消，不重试不确定结果；密钥只通过当前子进程环境变量传递。
     *
     * @param bizType 已验证的 FLIGHT 或 TRAIN
     * @param externalOrderNo 数据库记录中的真实供应商订单号
     * @param apiKey 当前用户凭证服务返回的解密结果
     * @return 供应商业务成功、明确失败或结果未知，均不包含密钥和原始响应
     */
    public CancellationResult cancel(String bizType, String externalOrderNo, String apiKey) {
        String server = switch (bizType == null ? "" : bizType) {
            case "FLIGHT" -> "flight";
            case "TRAIN" -> "train";
            default -> null;
        };
        if (server == null || externalOrderNo == null || externalOrderNo.isBlank()
                || apiKey == null || apiKey.isBlank()) {
            return new CancellationResult(Outcome.REJECTED, "INVALID_PARAMETER");
        }

        Process process = null;
        ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            ProcessBuilder builder = new ProcessBuilder(buildCommand(server, externalOrderNo));
            // 强制使用当前用户的 API Key，防止 CLI 误用宿主机已有的其他用户 OAuth 登录态。
            Map<String, String> environment = builder.environment();
            environment.put("TUNIU_API_KEY", apiKey);
            environment.put("TUNIU_AUTH_TYPE", "apiKey");
            environment.put("TUNIU_AUTH_SOURCE", "tuniu-cli");
            process = startProcess(builder);
            process.getOutputStream().close();
            Process running = process;
            // 并行消费两条输出管道，避免大量错误输出阻塞子进程；错误原文不进入日志或模型上下文。
            var output = readers.submit(() -> readOutput(running.getInputStream()));
            readers.submit(() -> running.getErrorStream().transferTo(OutputStream.nullOutputStream()));
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return new CancellationResult(Outcome.UNKNOWN, "CANCEL_TIMEOUT");
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return switch (exitCode) {
                    case 104, 108, 109, 110, 111, 112 ->
                            new CancellationResult(Outcome.REJECTED, "PROVIDER_AUTH_FAILED");
                    case 102, 103, 107 -> new CancellationResult(Outcome.REJECTED, "CLI_CONFIGURATION_ERROR");
                    default -> new CancellationResult(Outcome.UNKNOWN, "CANCEL_RESULT_UNKNOWN");
                };
            }
            // 进程退出码成功只表示 CLI 调用完成，仍需核实 MCP 内层的业务成功标志。
            String stdout = output.get(2, TimeUnit.SECONDS);
            if (stdout == null || !JSON.isValidObject(stdout)) {
                return new CancellationResult(Outcome.UNKNOWN, "CANCEL_RESULT_UNKNOWN");
            }
            Outcome outcome = inspectResponse(JSON.parseObject(stdout), 0);
            return new CancellationResult(outcome,
                    outcome == Outcome.CONFIRMED ? null
                            : outcome == Outcome.REJECTED ? "PROVIDER_CANCEL_REJECTED" : "CANCEL_RESULT_UNKNOWN");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new CancellationResult(Outcome.UNKNOWN, "CANCEL_INTERRUPTED");
        } catch (IOException exception) {
            return new CancellationResult(Outcome.UNKNOWN,
                    process == null ? "CLI_UNAVAILABLE" : "CANCEL_RESULT_UNKNOWN");
        } catch (Exception exception) {
            // 无法解析或等待输出失败时不能猜测供应商状态，也不向上暴露异常及可能含凭证的原文。
            return new CancellationResult(Outcome.UNKNOWN, "CANCEL_RESULT_UNKNOWN");
        } finally {
            stopProcess(process);
            readers.shutdownNow();
        }
    }

    /** 使用参数列表传递 JSON，Windows 直接运行 Node 入口，避免 cmd 对订单号和密钥进行二次解释。 */
    List<String> buildCommand(String server, String externalOrderNo) throws IOException {
        List<String> command = new ArrayList<>();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            Path entry = findWindowsEntry();
            Path node = entry.getParent().getParent().getParent().getParent().resolve("node.exe");
            command.add(Files.isRegularFile(node) ? node.toString() : "node");
            command.add(entry.toString());
        } else {
            command.add("tuniu");
        }
        command.addAll(List.of("call", server, "cancelOrder", "-a",
                JSON.toJSONString(Map.of("orderId", externalOrderNo)), "-t", "45"));
        return command;
    }

    /** 按 npm 全局安装约定从 PATH 查找 CLI 脚本，不读取用户凭证配置文件。 */
    private Path findWindowsEntry() throws IOException {
        String searchPath = System.getenv("PATH");
        if (searchPath != null) {
            for (String directory : searchPath.split(File.pathSeparator)) {
                if (directory.isBlank()) {
                    continue;
                }
                Path entry = Path.of(directory.replace("\"", ""))
                        .resolve("node_modules/tuniu-cli/bin/tuniu.js").toAbsolutePath().normalize();
                if (Files.isRegularFile(entry)) {
                    return entry;
                }
            }
        }
        throw new IOException("未找到途牛 CLI 安装入口。");
    }

    /** 启动已构造完成的子进程；独立边界便于测试时替换，避免访问真实订单。 */
    Process startProcess(ProcessBuilder builder) throws IOException {
        return builder.start();
    }

    /** 限制可解析输出大小，同时继续消费剩余数据，避免无限输出占用内存或堵塞管道。 */
    private String readOutput(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_OUTPUT_BYTES + 1);
        if (bytes.length > MAX_OUTPUT_BYTES) {
            input.transferTo(OutputStream.nullOutputStream());
            return null;
        }
        return new String(bytes, StandardCharsets.UTF_8).trim();
    }

    /**
     * 只沿已知 MCP 包装字段解析业务结果；内层失败优先，外层 success 不能替代业务成功。
     * 机票使用 success，火车票使用 successCode，兼容 text 为对象或字符串的响应。
     */
    private Outcome inspectResponse(Object value, int depth) {
        if (depth > 12) {
            return Outcome.UNKNOWN;
        }
        if (value instanceof String text) {
            return JSON.isValidObject(text) ? inspectResponse(JSON.parseObject(text), depth + 1) : Outcome.UNKNOWN;
        }
        if (!(value instanceof JSONObject object)) {
            return Outcome.UNKNOWN;
        }
        if (Boolean.FALSE.equals(object.get("success")) || Boolean.FALSE.equals(object.get("successCode"))
                || Boolean.TRUE.equals(object.get("isError"))) {
            return Outcome.REJECTED;
        }
        List<Object> nested = new ArrayList<>();
        for (String field : List.of("result", "structuredContent")) {
            if (object.containsKey(field)) {
                nested.add(object.get(field));
            }
        }
        if (object.get("content") instanceof JSONArray content) {
            // 空 content 仍代表存在 MCP 包装，不能退回使用外层的 success 误判取消成功。
            if (content.isEmpty()) {
                nested.add(null);
            }
            for (Object item : content) {
                nested.add(item instanceof JSONObject block ? block.get("text") : null);
            }
        }
        if (!nested.isEmpty()) {
            boolean confirmed = false;
            for (Object child : nested) {
                Outcome outcome = inspectResponse(child, depth + 1);
                if (outcome == Outcome.REJECTED) {
                    return Outcome.REJECTED;
                }
                confirmed |= outcome == Outcome.CONFIRMED;
            }
            return confirmed ? Outcome.CONFIRMED : Outcome.UNKNOWN;
        }
        return Boolean.TRUE.equals(object.get("success")) || Boolean.TRUE.equals(object.get("successCode"))
                ? Outcome.CONFIRMED : Outcome.UNKNOWN;
    }

    /** 结束仍在运行的取消进程及子进程，关闭输出流以释放阻塞的读取任务。 */
    private void stopProcess(Process process) {
        if (process == null) {
            return;
        }
        try {
            process.descendants().forEach(child -> {
                if (child.isAlive()) {
                    child.destroyForcibly();
                }
            });
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            process.getInputStream().close();
            process.getErrorStream().close();
        } catch (Exception ignored) {
            // 进程退出与清理可能并发发生，清理异常不得覆盖已得到的业务结果。
        }
    }

    /** 已确认取消、明确拒绝、结果未知三种供应商结果。 */
    public enum Outcome { CONFIRMED, REJECTED, UNKNOWN }

    /** 仅包含状态及内部错误码，不持有密钥或供应商原始响应。 */
    public record CancellationResult(Outcome outcome, String errorCode) {
    }
}
