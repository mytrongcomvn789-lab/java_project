package com.smartdrone.api.error;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
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
}
