package cn.lwccxy.trip.agent.conversation;

import cn.lwccxy.trip.business.conversation.domain.form.*;
import cn.lwccxy.trip.business.conversation.enums.MessageRole;
import cn.lwccxy.trip.business.conversation.mapper.ConversationMapper;
import cn.lwccxy.trip.business.conversation.mapper.MessageMapper;
import cn.lwccxy.trip.business.conversation.service.*;
import cn.lwccxy.trip.common.exception.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.*;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.GenerateReason;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
    "server.port=0",
    "spring.datasource.type=com.zaxxer.hikari.HikariDataSource",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.url=jdbc:h2:mem:conversation;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:user-auth-schema.sql,classpath:conversation-schema.sql",
    "sa-token.is-print=false"
})
@AutoConfigureMockMvc
class ConversationIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CORPTRIP_TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            if (!url.matches("jdbc:mysql://[^/]+/corptrip_history_verify_[a-z0-9]+(?:\\?.*)?")) {
                throw new IllegalStateException("Integration tests require a dedicated corptrip_history_verify_* database");
            }
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
            registry.add("spring.datasource.username", () -> System.getenv("CORPTRIP_TEST_DB_USER"));
            registry.add("spring.datasource.password", () -> System.getenv("CORPTRIP_TEST_DB_PASSWORD"));
            registry.add("spring.sql.init.mode", () -> "never");
            registry.add("server.port", () -> System.getProperty("corptrip.test.http-port", "8080"));
        }
    }

    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConversationService service;
    @Autowired ConversationCoordinator coordinator;
    @MockitoBean AgentReplyGateway agent;
    @MockitoSpyBean ConversationMapper conversationMapper;
    @MockitoSpyBean MessageMapper messageMapper;
    private String token;
    private long userId;
    private long conversationId;

    @BeforeEach void setup() throws Exception {
        jdbc.update("DELETE FROM t_message");
        jdbc.update("DELETE FROM t_conversation");
        String name = "c_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String password = UUID.randomUUID().toString();
        userId = call("POST", "/auth/register", null, Map.of("username", name, "password", password), 200).path("data").path("id").asLong();
        token = call("POST", "/auth/login", null, Map.of("username", name, "password", password), 200).path("data").path("tokenValue").asText();
        conversationId = create("first");
        when(agent.modelName()).thenReturn("test-model");
        when(agent.stream(anyLong(), anyString(), anyString())).thenAnswer(invocation -> Flux.defer(() -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_message WHERE conversation_id=? AND role='USER'", Long.class, conversationId)).isPositive();
            return Flux.just(new TextBlockDeltaEvent("r", "b", "answer"), new AgentResultEvent(new AssistantMessage("answer")));
        }));
    }

    private JsonNode call(String method, String path, String auth, Object body, int status) throws Exception {
        var request = MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method), path);
        if (auth != null) request.header("satoken", auth);
        if (body != null) request.contentType("application/json").content(json.writeValueAsBytes(body));
        var response = mvc.perform(request).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        return json.readTree(response.getContentAsString());
    }

    private long create(String title) throws Exception {
        return call("POST", "/agent/conversations", token, Map.of("title", title), 200).path("data").path("id").asLong();
    }
    private String path() { return "/agent/conversations/" + conversationId; }
    private MvcResult start(String content) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path() + "/messages").header("satoken", token)
                .contentType("application/json").content(json.writeValueAsBytes(Map.of("content", content))))
                .andReturn();
    }
    private String finish(MvcResult started) throws Exception {
        started.getAsyncResult(10000);
        return mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString();
    }
    private long append(MessageRole role, String content) {
        return service.append(userId, conversationId, role, content, null, null).getId();
    }
    private void oldTime() {
        jdbc.update("UPDATE t_conversation SET update_time='2020-01-01 00:00:00' WHERE id=?", conversationId);
    }
    private String feedbackPath(long id) { return path() + "/messages/" + id + "/feedback"; }

    @Test void actualHttpServerHistoryLifecycle() throws Exception {
        var client = java.net.http.HttpClient.newHttpClient();
        long second = http(client, "POST", "/agent/conversations", Map.of("title", "second"), 200).path("data").path("id").asLong();
        String sse = httpBody(client, "POST", path() + "/messages", Map.of("content", "network"), 200);
        assertThat(sse).contains("event:init", "event:delta", "event:complete");
        assertThat(http(client, "GET", "/agent/conversations", null, 200).path("data").get(0).path("id").asLong()).isEqualTo(conversationId);
        var messages = http(client, "GET", path()+"/messages?pageSize=1&pageNo=2", null, 200);
        assertThat(messages.path("totalCount").asInt()).isEqualTo(2);
        long assistant = messages.path("data").get(0).path("id").asLong();
        http(client, "PUT", path(), Map.of("title", "network renamed"), 200);
        for (String value : List.of("LIKE", "DISLIKE")) {
            assertThat(http(client, "PUT", feedbackPath(assistant), Map.of("feedback", value), 200).path("data").path("feedback").asText()).isEqualTo(value);
        }
        Map<String,Object> clear = new HashMap<>(); clear.put("feedback", null);
        assertThat(http(client, "PUT", feedbackPath(assistant), clear, 200).path("data").path("feedbackAt").isNull()).isTrue();
        http(client, "PUT", path(), Map.of("status", "ARCHIVED"), 200);
        http(client, "PUT", path(), Map.of("status", "CLOSED"), 200);
        assertThat(http(client, "GET", "/agent/conversations", null, 200).path("data").get(0).path("id").asLong()).isEqualTo(second);
        jdbc.update("UPDATE t_conversation SET user_id=? WHERE id=?", userId+100000, conversationId);
        http(client, "GET", path(), null, 404);
    }

    private JsonNode http(java.net.http.HttpClient client, String method, String url, Object body, int status) throws Exception {
        return json.readTree(httpBody(client, method, url, body, status));
    }
    private String httpBody(java.net.http.HttpClient client, String method, String url, Object body, int status) throws Exception {
        var publisher = body == null ? java.net.http.HttpRequest.BodyPublishers.noBody()
                : java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+url))
                .header("satoken", token).header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(15)).method(method, publisher).build();
        var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return response.body();
    }

    @Test void timeoutCancelsModelAndReleasesConversation() throws Exception {
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        doReturn(Flux.<AgentEvent>never().doOnSubscribe(s -> subscribed.countDown()).doOnCancel(cancelled::countDown))
                .when(agent).stream(anyLong(), anyString(), anyString());
        MvcResult started = start("timeout");
        assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();
        var context = (org.springframework.mock.web.MockAsyncContext) started.getRequest().getAsyncContext();
        var event = new jakarta.servlet.AsyncEvent(context);
        for (var listener : context.getListeners()) listener.onTimeout(event);
        assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
        try (var lease = coordinator.acquire(conversationId, true)) {
            assertThat(service.detail(userId, conversationId).getMessageCount()).isEqualTo(1);
        }
        call("PUT", path(), token, Map.of("status", "CLOSED"), 200);
    }

    @Test void ownershipAuthenticationAndProtectedFields() throws Exception {
        call("GET", "/agent/conversations", null, null, 401);
        call("POST", "/chat/chat", null, Map.of("question", "hello", "conversationId", conversationId), 401);
        for (String key : List.of("userId", "createdBy", "runtimeSessionId", "updateTime")) {
            call("POST", "/agent/conversations", token, Map.of("title", "bad", key, "override"), 400);
        }
        jdbc.update("UPDATE t_conversation SET user_id=? WHERE id=?", userId + 100000, conversationId);
        call("GET", path(), token, null, 404);
        call("GET", path() + "/messages", token, null, 404);
        call("PUT", path(), token, Map.of("title", "no"), 404);
        call("POST", path() + "/messages", token, Map.of("content", "no"), 404);
        call("PUT", feedbackPath(1), token, Map.of("feedback", "LIKE"), 404);
        call("POST", "/chat/chat", token, Map.of("question", "hello", "conversationId", conversationId), 404);
        verifyNoInteractions(agent);
    }

    @Test void creationPagingAndEqualTimestampOrdering() throws Exception {
        var created = call("POST", "/agent/conversations", token, Map.of(), 200).path("data");
        assertThat(created.path("title").asText()).isEqualTo("新会话");
        assertThat(created.path("messageCount").asInt()).isZero();
        long second = created.path("id").asLong();
        jdbc.update("UPDATE t_conversation SET update_time='2020-01-01 00:00:00' WHERE user_id=?", userId);
        var page = call("GET", "/agent/conversations?pageSize=1", token, null, 200);
        assertThat(page.path("totalCount").asLong()).isEqualTo(2);
        assertThat(page.path("totalPage").asLong()).isEqualTo(2);
        assertThat(page.path("data").get(0).path("id").asLong()).isEqualTo(second);
        assertThat(call("GET", "/agent/conversations?pageNo=100", token, null, 200).path("data")).isEmpty();
        assertThat(created.toString()).doesNotContain("runtimeSessionId", "traceId", "createdBy", "lastMessage");
        assertThat(jdbc.queryForObject("SELECT runtime_session_id FROM t_conversation WHERE id=?", String.class, second))
                .isNotBlank().isNotEqualTo(jdbc.queryForObject("SELECT runtime_session_id FROM t_conversation WHERE id=?", String.class, conversationId));
    }

    @Test void formBoundariesAndExplicitNull() throws Exception {
        for (String query : List.of("pageNo=0", "pageSize=0", "pageSize=101", "pageNo=abc", "status=INVALID", "status=")) {
            call("GET", "/agent/conversations?" + query, token, null, 400);
        }
        call("GET", "/agent/conversations/0", token, null, 400);
        call("GET", path() + "/messages?pageSize=101", token, null, 400);
        for (String title : List.of(" ", "x".repeat(201))) {
            call("POST", "/agent/conversations", token, Map.of("title", title), 400);
        }
        call("PUT", path(), token, Map.of(), 400);
        call("PUT", path(), token, Map.of("status", "BAD"), 400);
        for (String text : List.of(" ", "x".repeat(20001))) {
            call("POST", path() + "/messages", token, Map.of("content", text), 400);
        }
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var send = new SendMessageForm(); send.setContent("x".repeat(20000));
            assertThat(validator.validate(send)).isEmpty();
            var title = new CreateConversationForm(); title.setTitle("  " + "x".repeat(200) + "  ");
            assertThat(validator.validate(title)).isEmpty();
            assertThat(title.getTitle()).hasSize(200);
            var feedback = json.readValue("{\"feedback\":null}", MessageFeedbackForm.class);
            assertThat(validator.validate(feedback)).isEmpty();
            assertThat(validator.validate(json.readValue("{}", MessageFeedbackForm.class))).isNotEmpty();
        }
    }

    @Test void appendSortsUpdatesCountAndPreservesCreator() throws Exception {
        oldTime();
        long first = append(MessageRole.USER, "same");
        long second = append(MessageRole.ASSISTANT, "same");
        long third = append(MessageRole.USER, "same");
        jdbc.update("UPDATE t_message SET create_time='2020-01-01 00:00:00' WHERE conversation_id=?", conversationId);
        var page = call("GET", path() + "/messages?pageSize=2", token, null, 200);
        assertThat(page.path("totalCount").asLong()).isEqualTo(3);
        assertThat(page.path("totalPage").asLong()).isEqualTo(2);
        assertThat(page.path("data").get(0).path("id").asLong()).isEqualTo(first);
        assertThat(page.path("data").get(1).path("id").asLong()).isEqualTo(second);
        assertThat(call("GET", path() + "/messages?pageNo=2&pageSize=2", token, null, 200).path("data").get(0).path("id").asLong()).isEqualTo(third);
        assertThat(call("GET", path() + "/messages?pageNo=99", token, null, 200).path("data")).isEmpty();
        assertThat(service.detail(userId, conversationId).getMessageCount()).isEqualTo(3);
        assertThat(service.detail(userId, conversationId).getUpdateTime()).isAfter(LocalDateTime.of(2020, 1, 1, 0, 0));
        assertThat(jdbc.queryForList("SELECT created_by FROM t_message WHERE conversation_id=?", Long.class, conversationId)).containsOnly(userId);
        assertThat(page.toString()).doesNotContain("sequenceNo", "clientMessageId", "finishReason", "requestId", "updatedBy", "contentFormat");
    }

    @Test void lifecycleRenameNoopAndStateErrors() throws Exception {
        oldTime();
        call("PUT", path(), token, Map.of("title", "renamed"), 200);
        assertThat(service.detail(userId, conversationId).getUpdateTime()).isAfter(LocalDateTime.of(2020, 1, 1, 0, 0));
        for (String status : List.of("ARCHIVED", "CLOSED")) {
            call("PUT", path(), token, Map.of("status", status), 200);
            var before = service.detail(userId, conversationId).getUpdateTime();
            call("PUT", path(), token, Map.of("status", status), 200);
            assertThat(service.detail(userId, conversationId).getUpdateTime()).isEqualTo(before);
            call("PUT", path(), token, Map.of("status", "ACTIVE"), 409);
            call("POST", path() + "/messages", token, Map.of("content", "no"), 409);
            assertThat(call("GET", "/agent/conversations", token, null, 200).path("data")).isEmpty();
            assertThat(call("GET", "/agent/conversations?status=" + status, token, null, 200).path("totalCount").asLong()).isEqualTo(1);
        }
        assertThat(call("GET", "/agent/conversations?status=ALL", token, null, 200).path("totalCount").asLong()).isEqualTo(1);
    }

    @Test void feedbackSwitchCancelNoopAndNoReorder() throws Exception {
        long message = append(MessageRole.ASSISTANT, "reply");
        oldTime();
        var initial = service.detail(userId, conversationId);
        var originalMessage = jdbc.queryForObject("SELECT create_time FROM t_message WHERE id=?", LocalDateTime.class, message);
        var liked = call("PUT", feedbackPath(message), token, Map.of("feedback", "LIKE"), 200).path("data");
        assertThat(liked.path("feedback").asText()).isEqualTo("LIKE");
        assertThat(liked.path("feedbackAt").isNull()).isFalse();
        jdbc.update("UPDATE t_message SET feedback_at='2020-01-01 00:00:00' WHERE id=?", message);
        assertThat(call("PUT", feedbackPath(message), token, Map.of("feedback", "LIKE"), 200).path("data").path("feedbackAt").asText()).startsWith("2020-01-01");
        var disliked = call("PUT", feedbackPath(message), token, Map.of("feedback", "DISLIKE"), 200).path("data");
        assertThat(disliked.path("feedbackAt").asText()).doesNotStartWith("2020");
        assertThat(call("GET", path() + "/messages", token, null, 200).path("data").get(0).path("feedback").asText()).isEqualTo("DISLIKE");
        Map<String, Object> cancel = new HashMap<>(); cancel.put("feedback", null);
        var cleared = call("PUT", feedbackPath(message), token, cancel, 200).path("data");
        assertThat(cleared.path("feedback").isNull()).isTrue();
        assertThat(cleared.path("feedbackAt").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT feedback_at FROM t_message WHERE id=?", LocalDateTime.class, message)).isNull();
        assertThat(service.detail(userId, conversationId)).isEqualTo(initial);
        assertThat(jdbc.queryForObject("SELECT create_time FROM t_message WHERE id=?", LocalDateTime.class, message)).isEqualTo(originalMessage);
        call("PUT", path(), token, Map.of("status", "ARCHIVED"), 200);
        call("PUT", feedbackPath(message), token, Map.of("feedback", "LIKE"), 200);
        call("PUT", path(), token, Map.of("status", "CLOSED"), 200);
        call("PUT", feedbackPath(message), token, cancel, 200);
    }

    @Test void feedbackRejectsInvalidRoleMissingAndOtherConversation() throws Exception {
        long user = append(MessageRole.USER, "question");
        long assistant = append(MessageRole.ASSISTANT, "reply");
        call("PUT", feedbackPath(user), token, Map.of("feedback", "LIKE"), 400);
        for (Object form : List.of(Map.of(), Map.of("feedback", ""), Map.of("feedback", "like"), Map.of("feedback", "BAD"), Map.of("feedback", "LIKE", "feedbackAt", "2020"))) {
            call("PUT", feedbackPath(assistant), token, form, 400);
        }
        long other = create("other");
        call("PUT", "/agent/conversations/" + other + "/messages/" + assistant + "/feedback", token, Map.of("feedback", "LIKE"), 404);
        call("PUT", feedbackPath(99999999), token, Map.of("feedback", "LIKE"), 404);
    }

    @Test void concurrentAppendsKeepExactCount() throws Exception {
        var pool = Executors.newFixedThreadPool(6);
        try {
            var jobs = new ArrayList<Callable<Long>>();
            for (int i = 0; i < 12; i++) jobs.add(() -> append(MessageRole.USER, "same"));
            for (var future : pool.invokeAll(jobs)) assertThat(future.get()).isPositive();
            assertThat(service.detail(userId, conversationId).getMessageCount()).isEqualTo(12);
            assertThat(service.history(userId, conversationId, new HistoryPageForm()).getTotalCount()).isEqualTo(12);
        } finally { pool.shutdownNow(); }
    }

    @Test void appendRollsBackAfterMessageInsertOrCounterUpdate() {
        var before = service.detail(userId, conversationId);
        doThrow(new IllegalStateException("simulated counter failure")).when(conversationMapper).appended(eq(userId), eq(conversationId), nullable(String.class));
        assertThatThrownBy(() -> append(MessageRole.USER, "rollback")).isInstanceOf(IllegalStateException.class);
        assertThat(service.detail(userId, conversationId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_message WHERE conversation_id=?", Long.class, conversationId)).isZero();
        reset(conversationMapper);
        doThrow(new IllegalStateException("simulated message failure")).when(messageMapper).insert(any(cn.lwccxy.trip.business.conversation.domain.entity.MessageEntity.class));
        assertThatThrownBy(() -> append(MessageRole.USER, "rollback")).isInstanceOf(IllegalStateException.class);
        assertThat(service.detail(userId, conversationId)).isEqualTo(before);
    }

    @Test void concurrentFeedbackKeepsValueTimePairAndCount() throws Exception {
        long id = append(MessageRole.ASSISTANT, "reply");
        oldTime();
        var before = service.detail(userId, conversationId);
        var pool = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Callable<Void>>();
            for (String value : Arrays.asList("LIKE", "DISLIKE", null, "LIKE", null, "DISLIKE")) {
                tasks.add(() -> { var f = new MessageFeedbackForm(); f.setFeedback(value); service.feedback(userId, conversationId, id, f); return null; });
            }
            for (var f : pool.invokeAll(tasks)) f.get();
            var row = jdbc.queryForMap("SELECT feedback,feedback_at FROM t_message WHERE id=?", id);
            assertThat(row.get("feedback") == null).isEqualTo(row.get("feedback_at") == null);
            assertThat(service.detail(userId, conversationId)).isEqualTo(before);
        } finally { pool.shutdownNow(); }
    }

    @Test void ssePersistsCompleteReplyAndReusesOwnedSession() throws Exception {
        String first = finish(start("hello"));
        assertThat(first).contains("event:init", "event:delta", "event:complete").doesNotContain("event:error");
        assertThat(first.indexOf("event:init")).isLessThan(first.indexOf("event:complete"));
        finish(start("again"));
        String session = jdbc.queryForObject("SELECT runtime_session_id FROM t_conversation WHERE id=?", String.class, conversationId);
        verify(agent).stream(userId, session, "hello");
        verify(agent).stream(userId, session, "again");
        assertThat(service.detail(userId, conversationId).getMessageCount()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT content FROM t_message WHERE conversation_id=? AND role='ASSISTANT' ORDER BY id LIMIT 1", String.class, conversationId)).isEqualTo("answer");
        call("POST", "/chat/chat", token, Map.of("question", "hello", "sessionId", "untrusted"), 400);
    }

    @Test void modelFailureKeepsUserAndDoesNotInventReply() throws Exception {
        when(agent.stream(anyLong(), anyString(), anyString())).thenReturn(Flux.concat(
                Flux.just(new TextBlockDeltaEvent("r", "b", "partial")), Flux.error(new IllegalStateException("simulated"))));
        String response = finish(start("hello"));
        assertThat(response).contains("event:delta", "event:error").doesNotContain("event:complete", "simulated");
        assertThat(service.detail(userId, conversationId).getMessageCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_message WHERE conversation_id=? AND role='ASSISTANT'", Long.class, conversationId)).isZero();
        call("PUT", path(), token, Map.of("status", "ARCHIVED"), 200);
    }

    @Test void interruptedOrMissingFinalResultIsNotSuccess() throws Exception {
        when(agent.stream(anyLong(), anyString(), anyString())).thenReturn(Flux.just(new AgentResultEvent(
                new AssistantMessage("partial").withGenerateReason(GenerateReason.INTERRUPTED))));
        assertThat(finish(start("one"))).contains("event:error").doesNotContain("event:complete");
        when(agent.stream(anyLong(), anyString(), anyString())).thenReturn(Flux.just(new TextBlockDeltaEvent("r", "b", "partial")));
        assertThat(finish(start("two"))).contains("event:error").doesNotContain("event:complete");
        assertThat(service.detail(userId, conversationId).getMessageCount()).isEqualTo(2);
    }

    @Test void assistantPersistenceFailureNeverEmitsComplete() throws Exception {
        doAnswer(invocation -> {
            doThrow(new IllegalStateException("simulated persistence failure")).when(conversationMapper)
                    .appended(eq(userId), eq(conversationId), nullable(String.class));
            return Flux.just(new AgentResultEvent(new AssistantMessage("answer")));
        }).when(agent).stream(anyLong(), anyString(), anyString());
        assertThat(finish(start("hello"))).contains("event:error").doesNotContain("event:complete");
        assertThat(service.detail(userId, conversationId).getMessageCount()).isEqualTo(1);
    }

    @Test void serialAgentCallsAndBusyStateChanges() throws Exception {
        Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        CountDownLatch subscribed = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(agent.stream(anyLong(), anyString(), anyString())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) return sink.asFlux().doOnSubscribe(s -> subscribed.countDown());
            return Flux.just(new AgentResultEvent(new AssistantMessage("second")));
        });
        MvcResult first = start("one");
        assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();
        call("PUT", path(), token, Map.of("status", "CLOSED"), 409);
        var pool = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch trying = new CountDownLatch(1);
            Future<MvcResult> next = pool.submit(() -> { trying.countDown(); return start("two"); });
            assertThat(trying.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(calls.get()).isEqualTo(1);
            sink.tryEmitNext(new AgentResultEvent(new AssistantMessage("first")));
            sink.tryEmitComplete();
            assertThat(finish(first)).contains("event:complete");
            assertThat(finish(next.get(10, TimeUnit.SECONDS))).contains("event:complete");
            assertThat(calls.get()).isEqualTo(2);
            assertThat(service.detail(userId, conversationId).getMessageCount()).isEqualTo(4);
            call("PUT", path(), token, Map.of("status", "CLOSED"), 200);
        } finally { pool.shutdownNow(); }
    }
}
