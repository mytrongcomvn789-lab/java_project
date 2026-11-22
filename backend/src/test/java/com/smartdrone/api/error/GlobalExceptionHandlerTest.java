package com.smartdrone.api.error;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;

/** Guards the shared auth/RBAC error contract when merging the two branches. */
class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {
            "INVALID_CREDENTIALS", "FORBIDDEN", "SELF_ACCESS_CHANGE"
    })
    void businessErrorsKeepTheirStatusAndCode(ErrorCode code) {
        var response = handler.handleApiException(new ApiException(code));
        var body = assertInstanceOf(ProblemDetail.class, response.getBody());

        assertEquals(code.getStatus(), response.getStatusCode());
        assertEquals(code.getStatus().value(), body.getStatus());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.getHeaders().getContentType());
        assertEquals(code.name(), body.getProperties().get("code"));
        assertEquals(code.getDefaultMessage(), body.getProperties().get("message"));
        assertDoesNotThrow(() -> UUID.fromString((String) body.getProperties().get("traceId")));
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"NOT_FOUND", "CONFLICT"})
    void statusBasedErrorsDoNotFailWhenBusinessCodeIsAbsent(HttpStatus status) {
        var response = handler.handleApiException(new ApiException(status, "Requested action failed"));
        var body = assertInstanceOf(ProblemDetail.class, response.getBody());

        assertEquals(status, response.getStatusCode());
        assertEquals(status.value(), body.getStatus());
        assertEquals(status.name(), body.getProperties().get("code"));
        assertEquals("Requested action failed", body.getDetail());
        assertEquals(body.getDetail(), body.getProperties().get("message"));
        assertDoesNotThrow(() -> UUID.fromString((String) body.getProperties().get("traceId")));
    }

    // 2.4: lỗi database chưa được service bắt không được ra 500 hay lộ câu SQL.
    @Test
    void databaseConflictsAreMappedInsteadOfInternalError() {
        var duplicate = handler.handleDataIntegrity(new DataIntegrityViolationException(
                "could not execute statement", new RuntimeException("Violation of UNIQUE KEY constraint 'uk_secret'")));
        var body = assertInstanceOf(ProblemDetail.class, duplicate.getBody());
        assertEquals(409, duplicate.getStatusCode().value());
        assertEquals("DATA_CONFLICT", body.getProperties().get("code"));
        assertFalse(body.getDetail().contains("uk_secret"));

        var stale = handler.handleOptimisticLock(new ObjectOptimisticLockingFailureException("Order", UUID.randomUUID()));
        assertEquals(412, stale.getStatusCode().value());
        assertEquals("VERSION_CONFLICT", assertInstanceOf(ProblemDetail.class, stale.getBody()).getProperties().get("code"));

        var deadlock = handler.handleConcurrency(new CannotAcquireLockException("deadlock victim"));
        assertEquals(409, deadlock.getStatusCode().value());
        assertEquals("DATA_CONFLICT", assertInstanceOf(ProblemDetail.class, deadlock.getBody()).getProperties().get("code"));
    }
}
