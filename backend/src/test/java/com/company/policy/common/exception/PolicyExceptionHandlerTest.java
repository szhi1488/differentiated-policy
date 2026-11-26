package com.company.policy.common.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.policy.controller.PolicyApplyController;
import com.company.policy.controller.PolicyReviewController;
import com.company.policy.service.LoginSessionService;
import com.company.policy.service.PolicyApplyService;
import com.company.policy.service.PolicyReviewService;
import com.company.policy.service.PolicyTimeAdjustments;
import com.company.policy.vo.AuthSessionResponse;
import com.company.policy.vo.CurrentUserResponse;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

class PolicyExceptionHandlerTest {
    private static final String APPLY = "/api/policies";
    private static final String REVIEW = "/api/admin/policies";
    private static final String INTERNAL_DETAIL = "example-private-database-detail";

    private final PolicyApplyService applications = mock(PolicyApplyService.class);
    private final PolicyReviewService reviews = mock(PolicyReviewService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new PolicyApplyController(
                                        applications,
                                        sessions("APPLICANT"),
                                        mock(PolicyTimeAdjustments.class)),
                                new PolicyReviewController(sessions("ADMIN"), reviews))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .alwaysExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .build();
    }

    private static LoginSessionService sessions(String role) {
        var sessions = mock(LoginSessionService.class);
        var user =
                new CurrentUserResponse(
                        "1",
                        "open-id",
                        "tenant",
                        "测试人员",
                        java.util.List.of(role),
                        java.util.List.of(),
                        null);
        when(sessions.requireLogin(any())).thenReturn(new AuthSessionResponse(user, Instant.MAX));
        return sessions;
    }

    @ParameterizedTest
    @ValueSource(strings = {APPLY, REVIEW})
    void invalidPolicyIdReturnsPolicyValidationError(String path) throws Exception {
        mvc.perform(get(path + "/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("POLICY_VALIDATION_FAILED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {APPLY + "/drafts", REVIEW + "/42/review"})
    void malformedJsonUsesTheGlobalInvalidRequestContract(String path) throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @ParameterizedTest
    @ValueSource(strings = {APPLY, REVIEW})
    void missingQueryParameterUsesTheGlobalInvalidRequestContract(String path) throws Exception {
        mvc.perform(get(path + "/subsidy-timeline"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    static Stream<Arguments> storageFailures() {
        return Stream.of(
                Arguments.of(
                        new PessimisticLockingFailureException(INTERNAL_DETAIL),
                        409,
                        "POLICY_VERSION_CONFLICT"),
                Arguments.of(
                        new TransactionSystemException(
                                INTERNAL_DETAIL,
                                new PessimisticLockingFailureException(INTERNAL_DETAIL)),
                        409,
                        "POLICY_VERSION_CONFLICT"),
                Arguments.of(
                        new QueryTimeoutException(INTERNAL_DETAIL),
                        503,
                        "POLICY_STORAGE_UNAVAILABLE"),
                Arguments.of(
                        new CannotCreateTransactionException(INTERNAL_DETAIL),
                        503,
                        "POLICY_STORAGE_UNAVAILABLE"));
    }

    @ParameterizedTest
    @MethodSource("storageFailures")
    void applicantReadAndAdminWriteReturnTheSameStorageContract(
            RuntimeException failure, int expectedStatus, String code) throws Exception {
        when(applications.detail(any(), eq(42L))).thenThrow(failure);
        when(reviews.review(any(), eq(42L), any())).thenThrow(failure);

        mvc.perform(get(APPLY + "/42"))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(content().string(not(containsString(INTERNAL_DETAIL))));
        mvc.perform(
                        post(REVIEW + "/42/review")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"recordVersion":2,"submissionVersion":1,"decision":"APPROVE"}
                                        """))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(content().string(not(containsString(INTERNAL_DETAIL))));
    }
}
