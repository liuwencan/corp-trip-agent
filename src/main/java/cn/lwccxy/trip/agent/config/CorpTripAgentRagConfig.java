package cn.lwccxy.trip.agent.config;

import cn.lwccxy.trip.business.intent.domain.entity.IntentUtteranceEntity;
import cn.lwccxy.trip.business.intent.enums.IntentUtteranceStatus;
import cn.lwccxy.trip.business.intent.mapper.IntentUtteranceMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.reader.ReaderInput;
import io.agentscope.core.rag.reader.SplitStrategy;
import io.agentscope.core.rag.reader.TextReader;
import io.agentscope.core.rag.store.InMemoryStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 出差指南知识库配置。
 */
@Configuration
public class CorpTripAgentRagConfig {

    private static final String GUIDELINES_RESOURCE = "policy/corptrip_guidelines.docx";
    private static final String POLICY_RESOURCE = "policy/corptrip_policy.docx";
    private static final String WORD_NAMESPACE = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final int EMBEDDING_DIMENSIONS = 1024;

    private final IntentUtteranceMapper intentUtteranceMapper;
    private final String apiKey;

    /**
     * 创建知识库配置，并注入意图表达数据访问接口和百炼密钥。
     *
     * @param intentUtteranceMapper 意图口语化表达 Mapper
     * @param apiKey YAML 配置中的百炼 API Key
     */
    public CorpTripAgentRagConfig(IntentUtteranceMapper intentUtteranceMapper,
                                 @Value("${corp.trip.bailian.dashscope-api}") String apiKey) {
        // 知识库向量化与对话模型使用同一个配置项，避免凭据来源不一致。
        this.intentUtteranceMapper = intentUtteranceMapper;
        this.apiKey = apiKey;
    }


    /**
     * 创建并预加载企业出差指南知识库。
     *
     * @return 已完成文档向量化的内存知识库
     */
    @Bean(name = "corptripGuidelinesKnowledge")
    public Knowledge corptripGuidelinesKnowledge() {
        return createKnowledge(GUIDELINES_RESOURCE, "出差指南");
    }

    /**
     * 创建并预加载差旅政策知识库。
     *
     * @return 已完成文档向量化的内存知识库
     */
    @Bean(name = "corptripPolicyKnowledge")
    public Knowledge corptripPolicyKnowledge() {
        return createKnowledge(POLICY_RESOURCE, "差旅政策");
    }

    /**
     * 创建统一的意图向量知识库，并加载数据库中的所有启用口语化表达。
     *
     * <p>所有意图表达共用一个向量索引；每条文档通过 metadata 中的 {@code intent} 字段
     * 保存所属意图编码，召回后由上层根据该字段进行意图路由。</p>
     *
     * @return 包含全部意图表达的统一知识库
     */
    @Bean(name = "intentKnowledge")
    public Knowledge intentKnowledge() {
        // 只加载启用表达；后续后台禁用或删除后，重启即可从索引中移除对应文档。
        List<IntentUtteranceEntity> activeUtterances = intentUtteranceMapper.selectList(
                new LambdaQueryWrapper<IntentUtteranceEntity>()
                        .eq(IntentUtteranceEntity::getStatus, IntentUtteranceStatus.ACTIVE.name())
                        .orderByAsc(IntentUtteranceEntity::getId));
        // 所有意图表达共用一个 Store，避免为每个意图拆分索引导致召回范围被人为切断。
        EmbeddingModel embeddingModel = createEmbeddingModel("意图口语表达");
        return createIntentKnowledge(activeUtterances, embeddingModel);
    }


    /**
     * 为指定的 DOCX 资源创建独立的内存知识库并完成预向量化。
     *
     * @param resourcePath classpath 下的 DOCX 资源路径
     * @param documentName 文档名称，用于构造异常信息
     * @return 已加载文档向量的知识库
     */
    private Knowledge createKnowledge(String resourcePath, String documentName) {
        // 使用与向量存储一致的 1024 维嵌入模型，保证写入和检索的向量维度匹配。
        EmbeddingModel embeddingModel = createEmbeddingModel(documentName);
        Knowledge knowledge = SimpleKnowledge.builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(InMemoryStore.builder().dimensions(EMBEDDING_DIMENSIONS).build())
                .build();

        // 使用段落策略切分文档，启动时完成向量化，使后续 Agent 检索无需再次初始化文档。
        String documentText = readDocumentText(resourcePath, documentName);
        TextReader reader = new TextReader(512, SplitStrategy.PARAGRAPH, 50);
        List<io.agentscope.core.rag.model.Document> documents = reader
                .read(ReaderInput.fromString(documentText))
                .block();
        if (documents == null || documents.isEmpty()) {
            throw new IllegalStateException(documentName + "文档未解析出可用内容");
        }
        knowledge.addDocuments(documents).block();
        return knowledge;
    }

    /**
     * 创建统一意图知识库，并将所有启用表达加入同一个向量索引。
     *
     * @param utterances 所有意图下已启用的口语化表达
     * @param embeddingModel 当前启动共享的文本嵌入模型
     * @return 统一意图知识库
     */
    private Knowledge createIntentKnowledge(List<IntentUtteranceEntity> utterances,
                                            EmbeddingModel embeddingModel) {
        Knowledge knowledge = SimpleKnowledge.builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(InMemoryStore.builder().dimensions(EMBEDDING_DIMENSIONS).build())
                .build();
        if (utterances.isEmpty()) {
            return knowledge;
        }

        // 每条口语表达作为独立文档写入；payload 保留所属意图编码和来源记录 ID，供召回后路由及追踪。
        List<Document> documents = utterances.stream()
                .map(utterance -> toIntentDocument(utterance.getIntentCode(), utterance))
                .toList();
        knowledge.addDocuments(documents).block();
        return knowledge;
    }

    /**
     * 将数据库中的一条意图表达转换为带路由 metadata 的向量文档。
     *
     * @param intentCode 文档所属意图编码
     * @param utterance 数据库中的口语化表达
     * @return 内容为表达文本、payload 含意图编码的文档
     */
    Document toIntentDocument(String intentCode, IntentUtteranceEntity utterance) {
        String documentId = "intent-utterance-" + utterance.getId();
        DocumentMetadata metadata = DocumentMetadata.builder()
                .content(TextBlock.builder().text(utterance.getUtteranceText()).build())
                .docId(documentId)
                .chunkId(documentId)
                .addPayload("intent", intentCode)
                .addPayload("utterance_id", utterance.getId())
                .build();
        return new Document(metadata);
    }

    /**
     * 使用应用配置注入的百炼密钥创建文本嵌入模型。
     *
     * @param documentName 文档或知识库名称，用于错误定位
     * @return DashScope 文本嵌入模型
     */
    private EmbeddingModel createEmbeddingModel(String documentName) {
        // 配置为空时及时报告配置项和知识库名称，避免使用无效凭据执行向量化。
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("配置 corp.trip.bailian.dashscope-api 未配置，无法初始化" + documentName + "知识库");
        }

        // 复用统一配置中的密钥，保持知识库写入和检索使用相同的百炼凭据。
        return DashScopeTextEmbedding.builder()
                .apiKey(apiKey)
                .modelName("text-embedding-v3")
                .dimensions(EMBEDDING_DIMENSIONS)
                .build();
    }

    /**
     * 从 classpath 读取 DOCX 正文并提取段落文本。
     *
     * @return 出差指南的纯文本内容
     */
    private String readDocumentText(String resourcePath, String documentName) {
        try (InputStream resource = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (resource == null) {
                throw new IllegalStateException("找不到" + documentName + "资源: " + resourcePath);
            }
            try (ZipInputStream zipInput = new ZipInputStream(resource)) {
                ZipEntry entry;
                while ((entry = zipInput.getNextEntry()) != null) {
                    if ("word/document.xml".equals(entry.getName())) {
                        return extractParagraphText(zipInput);
                    }
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("读取" + documentName + "文档失败", exception);
        }
        throw new IllegalStateException(documentName + "文档缺少 word/document.xml: " + resourcePath);
    }

    /**
     * 解析 DOCX 的主文档 XML，并按 Word 段落边界拼接文本。
     *
     * @param documentXml DOCX 内部的主文档 XML 输入流
     * @return 拼接后的段落文本
     */
    private String extractParagraphText(InputStream documentXml) {
        try {
            // 禁止外部实体和外部资源访问，避免解析内部文档时引入外部内容。
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            org.w3c.dom.Document document = factory.newDocumentBuilder().parse(documentXml);

            NodeList paragraphs = document.getElementsByTagNameNS(WORD_NAMESPACE, "p");
            StringBuilder content = new StringBuilder();
            for (int index = 0; index < paragraphs.getLength(); index++) {
                // 每个 w:p 单独追加换行，保留指南原有的段落语义。
                appendNodeText(paragraphs.item(index), content);
                content.append('\n');
            }
            String result = content.toString().trim();
            if (result.isEmpty()) {
                throw new IllegalStateException("出差指南文档正文为空");
            }
            return result;
        } catch (Exception exception) {
            throw new IllegalStateException("解析出差指南 DOCX 内容失败", exception);
        }
    }

    /**
     * 递归提取 Word XML 节点中的文字、制表符和换行符。
     *
     * @param node 当前 XML 节点
     * @param content 文本输出缓冲区
     */
    private void appendNodeText(Node node, StringBuilder content) {
        if (WORD_NAMESPACE.equals(node.getNamespaceURI())) {
            if ("t".equals(node.getLocalName())) {
                content.append(node.getTextContent());
                return;
            }
            if ("tab".equals(node.getLocalName())) {
                content.append('\t');
                return;
            }
            if ("br".equals(node.getLocalName()) || "cr".equals(node.getLocalName())) {
                content.append('\n');
                return;
            }
        }
        NodeList children = node.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            appendNodeText(children.item(index), content);
        }
    }
}
