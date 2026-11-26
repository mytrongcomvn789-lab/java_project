package com.smartdrone.application.ai.summary;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.smartdrone.application.ai.AiDataAccessGuard;
import com.smartdrone.application.ai.AiDataBlock;
import com.smartdrone.application.ai.AiResult;
import com.smartdrone.application.ai.AiService;
import com.smartdrone.application.ai.AiSource;
import com.smartdrone.application.ai.AiTask;
import com.smartdrone.application.ai.AiUsage;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.orders.dto.OrderDetail;
import com.smartdrone.application.orders.dto.StatusHistoryView;
import com.smartdrone.domain.enums.FailureReason;
import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.enums.RoleName;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OrderSummaryServiceTest {
    static final Instant T0 = Instant.parse("2026-11-25T03:00:00Z");
    final ObjectMapper json = new ObjectMapper();
    final UUID user = UUID.randomUUID();
    final UUID orderId = UUID.randomUUID();
    final AiDataAccessGuard guard = mock(AiDataAccessGuard.class);
    final AiService ai = mock(AiService.class);
    final Clock clock = Clock.fixed(T0.plusSeconds(3600), ZoneOffset.UTC);
    final OrderSummaryService service = new OrderSummaryService(guard, ai, new OrderSummaryVerifier(),
            new OrderSummaryFallback(), clock);

    OrderDetail order(OrderStatus status, List<StatusHistoryView> history) {
        return new OrderDetail(orderId, 3, status, status.label(), UUID.randomUUID(), UUID.randomUUID(),
                null, null, history, T0, T0.plusSeconds(1200));
    }

    StatusHistoryView step(long version, OrderStatus to, long minutes, FailureReason reason, String note) {
        return new StatusHistoryView(UUID.randomUUID(), version, null, to, null, RoleName.DISPATCHER, null,
                note, reason, T0.plusSeconds(minutes * 60));
    }

    OrderDetail delivered() {
        return order(OrderStatus.DELIVERED, List.of(
                step(2, OrderStatus.IN_TRANSIT, 10, null, null),
                step(1, OrderStatus.APPROVED, 0, null, "Số điện thoại 0901234567"),
                step(3, OrderStatus.DELIVERED, 20, null, null)));
    }

    AiResult aiResult(String body, boolean degraded) throws Exception {
        JsonNode node = degraded ? null : json.readTree(body);
        return new AiResult(node, "m", false, degraded, degraded ? AiSource.FALLBACK : AiSource.PROVIDER,
                new AiUsage(1, 1, 2), UUID.randomUUID(), T0, degraded ? "AI_TIMEOUT" : null);
    }

    @Test
    void promptCarriesOnlyOrderedTimelineFactsWithoutFreeTextNotes() throws Exception {
        when(guard.orderForSummary(user, RoleName.CUSTOMER, orderId)).thenReturn(delivered());
        when(ai.run(eq(user), eq(RoleName.CUSTOMER), eq(AiTask.ORDER_SUMMARY), any()))
                .thenReturn(aiResult("{\"summary\":\"Đơn đã giao sau 20 phút.\",\"highlights\":[]}", false));
        service.summarize(user, RoleName.CUSTOMER, orderId);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiDataBlock>> blocks = ArgumentCaptor.forClass(List.class);
        verify(ai).run(eq(user), eq(RoleName.CUSTOMER), eq(AiTask.ORDER_SUMMARY), blocks.capture());
        String text = blocks.getValue().get(0).value();
        assertEquals("order_timeline", blocks.getValue().get(0).label());
        assertFalse(text.contains("0901234567"), "ghi chú tự do (có thể chứa SĐT) không được vào prompt");
        String tl = text.substring(text.indexOf("timeline:"));
        assertTrue(tl.indexOf("APPROVED") < tl.indexOf("IN_TRANSIT") && tl.indexOf("IN_TRANSIT") < tl.indexOf("DELIVERED"),
                "timeline phải theo thời gian");
        assertTrue(text.contains("minutesSincePrevious=10") && text.contains("totalMinutes: 20"));
    }

    @Test
    void verifiedAiTextIsReturnedWithDeterministicTimeline() throws Exception {
        when(guard.orderForSummary(user, RoleName.CUSTOMER, orderId)).thenReturn(delivered());
        when(ai.run(any(), any(), any(), any())).thenReturn(aiResult(
                "{\"summary\":\"Đơn được duyệt rồi giao xong sau 20 phút.\",\"highlights\":[\"Giai đoạn bay mất 10 phút.\"]}",
                false));
        AiResult r = service.summarize(user, RoleName.CUSTOMER, orderId);
        assertFalse(r.degraded());
        assertEquals(AiSource.PROVIDER, r.source());
        assertEquals("AI_VERIFIED", r.result().path("mode").asString());
        assertEquals("Đơn được duyệt rồi giao xong sau 20 phút.", r.result().path("summary").asString());
        assertEquals(3, r.result().path("timeline").size());
        assertEquals("APPROVED", r.result().path("timeline").get(0).path("status").asString());
    }

    @Test
    void inventedNumberFallsBackToDeterministicSummary() throws Exception {
        when(guard.orderForSummary(user, RoleName.CUSTOMER, orderId)).thenReturn(delivered());
        when(ai.run(any(), any(), any(), any())).thenReturn(
                aiResult("{\"summary\":\"Đơn giao xong sau 45 phút.\",\"highlights\":[]}", false));
        AiResult r = service.summarize(user, RoleName.CUSTOMER, orderId);
        assertTrue(r.degraded());
        assertEquals(AiSource.FALLBACK, r.source());
        assertEquals("DETERMINISTIC_FALLBACK", r.result().path("mode").asString());
        assertEquals("AI_RESPONSE_INVALID", r.fallbackReason());
        assertFalse(r.result().path("summary").asString().contains("45"));
        assertTrue(r.result().path("summary").asString().contains("Đã giao"));
    }

    @Test
    void linksAndEmailsInAiTextAreRejected() throws Exception {
        when(guard.orderForSummary(user, RoleName.CUSTOMER, orderId)).thenReturn(delivered());
        for (String bad : List.of("Xem tại https://evil.example", "Liên hệ a@b.c")) {
            org.mockito.Mockito.doReturn(aiResult("{\"summary\":\"" + bad + "\",\"highlights\":[]}", false))
                    .when(ai).run(any(), any(), any(), any());
            assertTrue(service.summarize(user, RoleName.CUSTOMER, orderId).degraded(), bad);
        }
    }

    @Test
    void providerFailureAndDisabledAiUseFallbackButBudgetAndRateLimitDoNot() throws Exception {
        when(guard.orderForSummary(user, RoleName.CUSTOMER, orderId)).thenReturn(delivered());
        when(ai.run(any(), any(), any(), any())).thenThrow(new ApiException(ErrorCode.AI_TIMEOUT));
        AiResult r = service.summarize(user, RoleName.CUSTOMER, orderId);
        assertTrue(r.degraded());
        assertEquals("AI_TIMEOUT", r.fallbackReason());
        assertEquals(AiUsage.NONE, r.usage());
        org.mockito.Mockito.doReturn(aiResult(null, true)).when(ai).run(any(), any(), any(), any());
        assertEquals("AI_TIMEOUT", service.summarize(user, RoleName.CUSTOMER, orderId).fallbackReason());
        for (ErrorCode code : List.of(ErrorCode.AI_RATE_LIMITED, ErrorCode.AI_BUDGET_EXCEEDED)) {
            org.mockito.Mockito.doThrow(new ApiException(code)).when(ai).run(any(), any(), any(), any());
            var ex = assertThrows(ApiException.class, () -> service.summarize(user, RoleName.CUSTOMER, orderId));
            assertEquals(code, ex.getErrorCode());
        }
    }

    @Test
    void failedOrderFallbackMentionsFixedReasonCodeNotFreeText() throws Exception {
        when(guard.orderForSummary(user, RoleName.CUSTOMER, orderId)).thenReturn(order(OrderStatus.FAILED, List.of(
                step(1, OrderStatus.IN_TRANSIT, 0, null, null),
                step(2, OrderStatus.FAILED, 15, FailureReason.RECIPIENT_ABSENT, "gọi 0909999999 không bắt máy"))));
        when(ai.run(any(), any(), any(), any())).thenThrow(new ApiException(ErrorCode.AI_NOT_CONFIGURED));
        String text = service.summarize(user, RoleName.CUSTOMER, orderId).result().toString();
        assertTrue(text.contains("Người nhận không có mặt"));
        assertFalse(text.contains("0909999999"));
        assertTrue(text.contains("giao lại hoặc hoàn hàng"));
    }

    @Test
    void orderWithoutHistoryStillSummarisesAndOutOfScopeOrderNeverReachesAi() throws Exception {
        when(guard.orderForSummary(user, RoleName.CUSTOMER, orderId)).thenReturn(order(OrderStatus.PENDING, List.of()));
        when(ai.run(any(), any(), any(), any())).thenThrow(new ApiException(ErrorCode.AI_NOT_CONFIGURED));
        AiResult r = service.summarize(user, RoleName.CUSTOMER, orderId);
        assertEquals(0, r.result().path("timeline").size());
        assertTrue(r.result().path("summary").asString().contains("Chưa có lịch sử"));

        UUID other = UUID.randomUUID();
        when(guard.orderForSummary(user, RoleName.CUSTOMER, other)).thenThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        org.mockito.Mockito.clearInvocations(ai);
        assertThrows(ApiException.class, () -> service.summarize(user, RoleName.CUSTOMER, other));
        verify(ai, never()).run(any(), any(), any(), any());
    }

    @Test
    void verifierAllowsReformattedTimesButRejectsOtherNumbers() {
        var facts = OrderSummaryFacts.from(delivered(), T0.plusSeconds(3600));
        var v = new OrderSummaryVerifier();
        // 03:00 trong dữ kiện có thể bị viết thành "3 giờ" (03 và 3 là cùng một số).
        assertDoesNotThrow(() -> v.verify(json.readTree(
                "{\"summary\":\"Duyệt lúc 3 giờ, 20 phút sau thì giao xong.\",\"highlights\":[]}"), facts));
        assertThrows(ApiException.class, () -> v.verify(json.readTree(
                "{\"summary\":\"Giao trong 99 phút.\",\"highlights\":[]}"), facts));
        assertThrows(ApiException.class, () -> v.verify(json.readTree(
                "{\"summary\":\"ok\",\"highlights\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}"), facts));
        assertThrows(ApiException.class, () -> v.verify(json.readTree("{\"summary\":\"ok\"}"), facts));
    }
}
