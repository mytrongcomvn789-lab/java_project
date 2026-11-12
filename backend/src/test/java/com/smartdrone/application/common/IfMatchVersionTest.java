package com.smartdrone.application.common;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class IfMatchVersionTest {

    @Test
    void parsesQuotedWeakAndBareValues() {
        assertEquals(5, IfMatchVersion.parse("\"5\""));
        assertEquals(7, IfMatchVersion.parse("W/\"7\""));
        assertEquals(9, IfMatchVersion.parse(" 9 "));
    }

    @Test
    void missingHeaderIsPreconditionRequired() {
        assertEquals(ErrorCode.PRECONDITION_REQUIRED,
                assertThrows(ApiException.class, () -> IfMatchVersion.parse(null)).getErrorCode());
        assertEquals(ErrorCode.PRECONDITION_REQUIRED,
                assertThrows(ApiException.class, () -> IfMatchVersion.parse("  ")).getErrorCode());
    }

    @Test
    void garbageOrNegativeIsValidationError() {
        for (String bad : new String[] {"abc", "\"x\"", "-1", "1.5"}) {
            assertEquals(ErrorCode.VALIDATION_ERROR,
                    assertThrows(ApiException.class, () -> IfMatchVersion.parse(bad)).getErrorCode(), bad);
        }
    }

    @Test
    void formatProducesQuotedEtag() {
        assertEquals("\"5\"", IfMatchVersion.format(5));
    }
}