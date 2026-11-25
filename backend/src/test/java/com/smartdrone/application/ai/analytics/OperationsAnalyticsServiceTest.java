package com.smartdrone.application.ai.analytics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.smartdrone.application.ai.*;
import com.smartdrone.application.common.*;
import com.smartdrone.domain.enums.RoleName;
import tools.jackson.databind.ObjectMapper;

class OperationsAnalyticsServiceTest {
    private final OperationsDataReader reader = mock(OperationsDataReader.class);
    private final AiService ai = mock(AiService.class);
    private final UUID actor = UUID.randomUUID();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"),ZoneOffset.UTC);
    private final OperationsAnalyticsService service = new OperationsAnalyticsService(reader,new OperationsRules(),
            new OperationsNarrative(),ai,clock);
    private void data(long n) {
        when(reader.read(actor,null,null,null,null)).thenReturn(OperationsRulesTest.data(n,0,0,n,0));
    }
    private AiResult run() { return service.analyze(actor,RoleName.LOGISTICS_MANAGER,null,null,null,null); }
    @Test void nonManagerFailsBeforeReadingOrCallingProvider() {
        var ex=assertThrows(ApiException.class, () -> service.analyze(actor,RoleName.CUSTOMER,null,null,null,null));
        assertEquals(ErrorCode.FORBIDDEN,ex.getErrorCode()); verifyNoInteractions(reader,ai);
    }
    @Test void noOrdersReturnsReportWithoutCallingAi() {
        data(0); var r=run();
        assertTrue(r.degraded()); assertEquals("NO_ORDERS_IN_SCOPE",r.fallbackReason());
        assertEquals(0,r.result().path("report").path("summary").path("orders").path("total").asLong());
        verifyNoInteractions(ai);
    }
    @Test void providerFailuresStillReturnVerifiedNumbersEvenWithSharedFallbackDisabled() {
        for (ErrorCode code : List.of(ErrorCode.AI_NOT_CONFIGURED,ErrorCode.AI_TIMEOUT,
                ErrorCode.AI_PROVIDER_UNAVAILABLE,ErrorCode.AI_RESPONSE_INVALID)) {
            reset(ai); data(20);
            when(ai.run(eq(actor),eq(RoleName.LOGISTICS_MANAGER),eq(AiTask.OPS_ANALYSIS),anyList()))
                    .thenThrow(new ApiException(code));
            var r=run(); assertEquals(AiSource.FALLBACK,r.source()); assertEquals(code.name(),r.fallbackReason());
            assertEquals(20,r.result().path("report").path("summary").path("orders").path("total").asLong());
        }
    }
    @Test void rateLimitAndBudgetAreNotSwallowedByFallback() {
        for (ErrorCode code : List.of(ErrorCode.AI_RATE_LIMITED,ErrorCode.AI_BUDGET_EXCEEDED,ErrorCode.FORBIDDEN)) {
            reset(ai); data(20);
            when(ai.run(any(),any(),any(),anyList())).thenThrow(new ApiException(code));
            assertEquals(code,assertThrows(ApiException.class,this::run).getErrorCode());
        }
    }
    @Test void sharedDegradedResultRetainsRequestMetadata() {
        data(20); UUID request=UUID.randomUUID();
        when(ai.run(any(),any(),any(),anyList())).thenReturn(new AiResult(null,null,false,true,
                AiSource.FALLBACK,AiUsage.NONE,request,clock.instant(),"AI_TIMEOUT"));
        var r=run(); assertEquals(request,r.requestId()); assertTrue(r.degraded());
        assertEquals("AI_TIMEOUT",r.fallbackReason()); assertNotNull(r.result());
    }
    @Test void ungroundedProviderOutputFallsBackWithoutLosingMeasurements() {
        data(20);
        when(ai.run(any(),any(),any(),anyList())).thenReturn(new AiResult(
                new ObjectMapper().readTree("{\"insights\":[\"999 orders\"],\"recommendations\":[]}"),
                "fixture",true,false,AiSource.MOCK,new AiUsage(1,1,2),UUID.randomUUID(),clock.instant(),null));
        var r=run(); assertEquals("AI_RESPONSE_INVALID",r.fallbackReason());
        assertFalse(r.result().toString().contains("999 orders")); assertTrue(r.degraded());
    }
}
