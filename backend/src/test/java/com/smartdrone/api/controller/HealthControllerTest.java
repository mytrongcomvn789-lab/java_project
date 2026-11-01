package com.smartdrone.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;

import org.junit.jupiter.api.Test;

class HealthControllerTest {

    @Test
    void healthReturnsOk() {
        Map<String, Object> body = new HealthController().health();

        assertEquals("ok", body.get("status"));
        assertNotNull(body.get("time"));
    }
}
