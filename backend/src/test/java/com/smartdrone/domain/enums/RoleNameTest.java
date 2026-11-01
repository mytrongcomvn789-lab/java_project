package com.smartdrone.domain.enums;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RoleNameTest {

    @Test
    void hasFiveActors() {
        assertEquals(5, RoleName.values().length);
    }
}
