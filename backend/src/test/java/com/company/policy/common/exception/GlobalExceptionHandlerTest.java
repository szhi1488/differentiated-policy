package com.company.policy.common.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final TestController controller = new TestController();
    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(handler).build();
    private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

    @BeforeEach
    void captureHandlerLogs() {
        captured.start();
        logger.addAppender(captured);
    }

    @AfterEach
    void stopCapturingHandlerLogs() {
        logger.detachAppender(captured);
        captured.stop();
    }

    @Test
    void unsupportedMethodReturns405InsteadOf500() throws Exception {
        mvc.perform(get("/test/body"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(header().string("Allow", "POST"))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(captured.list).noneMatch(event -> event.getLevel() == Level.ERROR);
    }

    @Test
    void missingResourcePreserves404Status() throws Exception {
        mvc.perform(get("/test/missing")).andExpect(status().isNotFound());
    }

    @Test
    void classifiesPersistenceFailuresForAnEndpointWithoutDomainOverrides() throws Exception {
        readWithFailure(new CannotAcquireLockException("lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
        for (var failure :
                List.of(
                        new CannotGetJdbcConnectionException("connection"),
                        new CannotCreateTransactionException("transaction"))) {
            readWithFailure(failure)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("STORAGE_UNAVAILABLE"));
        }
        var badSql = new BadSqlGrammarException("read", "private-sql", new SQLException());
        var response =
                readWithFailure(badSql)
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                        .andExpect(header().exists("X-Request-Id"))
                        .andReturn()
                        .getResponse();
        assertThat(onlyError().getFormattedMessage())
                .contains(response.getHeader("X-Request-Id"), "BadSqlGrammarException")
                .doesNotContain("private-sql");
    }

    private ResultActions readWithFailure(RuntimeException failure) throws Exception {
        controller.readFailure = failure;
        return mvc.perform(get("/test/records/1"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void unsupportedContentTypeReturns415InsteadOf500() throws Exception {
        mvc.perform(post("/test/body").contentType(MediaType.TEXT_PLAIN).content("test"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void unexpectedFailureLinksOneSafeDiagnosticToTheResponse() throws Exception {
        String suppliedId = "45a36310-847a-4af3-bbe8-41ea07f7f901";
        var response =
                mvc.perform(
                                post("/test/failure")
                                        .queryParam("state", "query-private-marker")
                                        .header("X-Request-Id", suppliedId)
                                        .header("Authorization", "Bearer header-private-marker")
                                        .contentType(MediaType.TEXT_PLAIN)
                                        .content("body-private-marker"))
                        .andExpect(status().isInternalServerError())
                        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                        .andExpect(jsonPath("$.message").value("服务暂时不可用，请稍后重试。"))
                        .andExpect(jsonPath("$.*", hasSize(2)))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().exists("X-Request-Id"))
                        .andReturn()
                        .getResponse();
        String requestId = response.getHeader("X-Request-Id");
        assertThat(UUID.fromString(requestId).toString()).isEqualTo(requestId);
        assertThat(requestId).isNotEqualTo(suppliedId);
        assertThat(onlyError().getFormattedMessage())
                .contains(requestId, "IllegalStateException", "IllegalArgumentException")
                .containsPattern("TestController\\.failure:\\d+")
                .containsPattern("TestController\\.readSnapshot:\\d+")
                .doesNotContain(
                        suppliedId,
                        "outer-private-marker",
                        "cause-private-marker",
                        "suppressed-private-marker",
                        "/test/failure",
                        "query-private-marker",
                        "header-private-marker",
                        "body-private-marker");

        captured.list.clear();
        var repeated =
                mvc.perform(post("/test/failure").contentType(MediaType.TEXT_PLAIN).content("再次请求"))
                        .andExpect(status().isInternalServerError())
                        .andReturn()
                        .getResponse()
                        .getHeader("X-Request-Id");
        assertThat(UUID.fromString(repeated).toString()).isEqualTo(repeated);
        assertThat(repeated).isNotEqualTo(requestId);
        assertThat(onlyError().getFormattedMessage()).contains(repeated).doesNotContain(requestId);
    }

    @Test
    void cyclicCausesRecordEachTypeOnceAndPrioritizeApplicationFramesAfterTheFirstFrame() {
        var outer = new IllegalStateException("outer-private-marker");
        var cause = new IllegalArgumentException("cause-private-marker", outer);
        outer.initCause(cause);
        outer.setStackTrace(
                new StackTraceElement[] {
                    new StackTraceElement("example.Driver", "execute", "Driver.java", 10),
                    new StackTraceElement("example.Adapter", "dispatch", "Adapter.java", 11),
                    new StackTraceElement("com.company.policy.One", "read", "One.java", 12),
                    new StackTraceElement("com.company.policy.Two", "read", "Two.java", 13),
                    new StackTraceElement("com.company.policy.Three", "read", "Three.java", 14),
                    new StackTraceElement("com.company.policy.Four", "read", "Four.java", 15)
                });
        cause.setStackTrace(new StackTraceElement[0]);

        handler.handleUnexpected(outer);
        assertThat(onlyError().getFormattedMessage())
                .containsOnlyOnce("IllegalStateException")
                .containsOnlyOnce("IllegalArgumentException")
                .contains("Driver.execute:10", "One.read:12", "Two.read:13", "Three.read:14")
                .doesNotContain("Adapter.dispatch:11", "Four.read:15");
    }

    private ILoggingEvent onlyError() {
        var errors =
                captured.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
        assertThat(errors).hasSize(1);
        var event = errors.get(0);
        assertThat(event.getThrowableProxy()).isNull();
        if (event.getArgumentArray() != null) {
            assertThat(event.getArgumentArray()).noneMatch(Throwable.class::isInstance);
        }
        return event;
    }

    @RestController
    static class TestController {
        private RuntimeException readFailure;

        @GetMapping("/test/records/{id}")
        long read(@PathVariable long id) {
            throw readFailure;
        }

        @PostMapping(value = "/test/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        String body(@RequestBody String body) {
            return body;
        }

        @PostMapping("/test/failure")
        String failure(@RequestBody String body) {
            try {
                return readSnapshot(body);
            } catch (IllegalArgumentException cause) {
                var outer = new IllegalStateException("outer-private-marker", cause);
                outer.addSuppressed(new IllegalStateException("suppressed-private-marker"));
                throw outer;
            }
        }

        private String readSnapshot(String body) {
            throw new IllegalArgumentException("cause-private-marker " + body);
        }
    }
}
