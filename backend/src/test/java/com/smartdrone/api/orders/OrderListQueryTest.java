package com.smartdrone.api.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.orders.dto.OrderListQuery;

class OrderListQueryTest {

    @Test
    void defaultsSortByCreatedAtDescThenId() {
        var q = OrderListQuery.of(null, null, null, null, null, 1, 20, null);
        assertEquals("createdAt: DESC,id: ASC", q.sort().toString());
    }

    @Test
    void rejectsPageSizeOver100() {
        assertThrows(ApiException.class,
                () -> OrderListQuery.of(null, null, null, null, null, 1, 101, null));
    }

    @Test
    void rejectsSortFieldOutsideAllowList() {
        assertThrows(ApiException.class,
                () -> OrderListQuery.of(null, null, null, null, null, 1, 20, "customerId,asc"));
    }

    @Test
    void rejectsBadStatusUuidAndDates() {
        assertThrows(ApiException.class,
                () -> OrderListQuery.of(null, "ABC", null, null, null, 1, 20, null));
        assertThrows(ApiException.class,
                () -> OrderListQuery.of("khong-phai-uuid", null, null, null, null, 1, 20, null));
        assertThrows(ApiException.class,
                () -> OrderListQuery.of(null, null, "2026-11-10T00:00:00Z", "2026-11-09T00:00:00Z", null, 1, 20, null));
    }
}