package cn.lwccxy.trip.agent.config;

import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 差旅Agent所有MCP配置
 */
@Configuration
public class CorpTripAgentMcpConfig {

    private static final Logger logger = LoggerFactory.getLogger(CorpTripAgentMcpConfig.class);

    @Bean(name = "weatherMcpClient")
    public McpClientWrapper weatherMcpClient() {
        logger.info("[CorpTripAgentMcpConfig] 阿里云MCP天气工具加载中.............");

        McpClientWrapper mcpClientWrapper = null;
        try {
            mcpClientWrapper = McpClientBuilder.create("weather-mcp")
                    .streamableHttpTransport("http://mcpservergateway.market.alicloudapi.com/mcpnacos/cmapi00074002/eyJhcHBDb2RlIjoiMWFlN2FkOWY0YmU1NDFjNjllZjAxYzE2ZjMwMGM0YTMiLCJzIjoiQ2xvdWRfTWFya2V0In0=")
                    .timeout(Duration.ofSeconds(30)) // 设置MCP工具调用超时30S
                    .initializationTimeout(Duration.ofSeconds(15)) // 设置MCP工具握手超时时间15s
                    .buildAsync()
                    .block();

            logger.info("[CorpTripAgentMcpConfig] 阿里云MCP天气工具成功");
            return mcpClientWrapper;
        } catch (Exception e) {
            logger.warn("[CorpTripAgentMcpConfig] 阿里云MCP天气工具加载失败, 原因是：{}", e.getMessage());
            return null;
        }
    }

}
