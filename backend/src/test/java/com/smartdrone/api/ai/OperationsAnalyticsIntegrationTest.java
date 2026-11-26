package com.smartdrone.api.ai;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.context.ActiveProfiles;
import com.smartdrone.application.ai.*;
import com.smartdrone.application.auth.AccessTokenIssuer;
import com.smartdrone.application.common.*;
import com.smartdrone.domain.entity.*;
import com.smartdrone.domain.enums.*;
import com.smartdrone.infrastructure.persistence.*;
import tools.jackson.databind.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=jdbc:h2:mem:ops75-test;MODE=MSSQLServer;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "app.jwt.key=ops75-test-signing-key-at-least-32-characters-long",
    "smartdrone.ai.provider=AUTO","smartdrone.ai.gemini.api-key=","smartdrone.ai.fallback-enabled=false",
    "smartdrone.ai.rate-limit.per-user-per-minute=1000","smartdrone.ai.rate-limit.global-per-minute=1000",
    "logging.level.com.smartdrone.api.filter.RequestLoggingFilter=WARN"
})
@ActiveProfiles("h2")
@Import(OperationsAnalyticsIntegrationTest.ProviderConfig.class)
class OperationsAnalyticsIntegrationTest {
    static class FixtureProvider implements AiProvider {
        AtomicInteger calls=new AtomicInteger();
        volatile String text;
        volatile ApiException failure;
        volatile AiRequest last;
        public AiResponse generate(AiRequest request) {
            calls.incrementAndGet(); last=request;
            if(failure!=null) throw failure;
            return new AiResponse(request.requestId(),text,"fixture-ops75",AiSource.MOCK,false,
                    new AiUsage(1,1,2),1,Instant.now());
        }
    }
    @TestConfiguration static class ProviderConfig {
        @Bean @Primary FixtureProvider opsFixtureProvider() { return new FixtureProvider(); }
    }
    @Autowired FixtureProvider provider;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired StationRepository stations;
    @Autowired OrderRepository orders;
    @Autowired OrderStatusHistoryRepository history;
    @Autowired StaffStationAssignmentRepository assignments;
    @Autowired AccessTokenIssuer tokens;
    @LocalServerPort int port;
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=HttpClient.newHttpClient();
    record Identity(User user,String token) {}
    record Fixture(Station a,Station b,Station outside,Identity manager) {}
    @BeforeEach void resetProvider() {
        provider.calls.set(0); provider.failure=null; provider.last=null;
        provider.text="{\"insights\":[\"ORDER_VOLUME\",\"DELIVERY_OUTCOMES\"],\"recommendations\":[\"ORDER_VOLUME\"]}";
    }
    Identity identity(RoleName role) {
        var u=users.saveAndFlush(new User(UUID.randomUUID()+"@ops75.test","unused","fixture",
                roles.findByName(role).orElseThrow()));
        return new Identity(u,tokens.issue(u));
    }
    Station station(String name) { return stations.saveAndFlush(new Station(name,10.77,106.7,5)); }
    static Instant vn(String s) { return LocalDateTime.parse(s).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(); }
    UUID order(Station a,Station b,OrderStatus status,Instant at) {
        var o=new Order(UUID.randomUUID(),a.getId(),b.getId(),
                new RecipientSnapshot("PRIVATE RECIPIENT","0900000000","PRIVATE ADDRESS",10.77,106.7),at);
        o.changeStatus(status); return orders.saveAndFlush(o).getId();
    }
    void hist(UUID order,long version,OrderStatus status,OrderAction action,Instant at) {
        history.saveAndFlush(new OrderStatusHistory(order,version,null,status,action,null,null,
                ChangeSource.SYSTEM,null,null,null,at));
    }
    Fixture seed() {
        var a=station("visible A"); var b=station("visible B"); var c=station("PRIVATE OUTSIDE");
        var m=identity(RoleName.LOGISTICS_MANAGER);
        assignments.saveAndFlush(new StaffStationAssignment(m.user(),a,Instant.now()));
        assignments.saveAndFlush(new StaffStationAssignment(m.user(),b,Instant.now()));
        for(int i=0;i<20;i++) {
            var id=order(a,b,i<16?OrderStatus.DELIVERED:OrderStatus.FAILED,
                    vn(i<10?"2026-11-01T00:00:00":"2026-11-02T23:59:59"));
            if(i<2) {
                hist(id,1,OrderStatus.IN_TRANSIT,OrderAction.DISPATCH,vn("2026-11-02T10:00:00"));
                hist(id,2,OrderStatus.DELIVERED,OrderAction.CONFIRM,vn("2026-11-02T10:05:00"));
            }
        }
        order(c,c,OrderStatus.FAILED,vn("2026-11-01T01:00:00"));
        order(a,b,OrderStatus.CANCELLED,vn("2026-11-03T00:00:00"));
        return new Fixture(a,b,c,m);
    }
    String body(UUID station,String basis) {
        return "{\"from\":\"2026-11-01\",\"to\":\"2026-11-02\",\"dateBasis\":\""+basis+"\""
                +(station==null?"":",\"stationId\":\""+station+"\"")+"}";
    }
    HttpResponse<String> request(String method,String path,String token,String body) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(30));
        if(token!=null)b.header("Authorization","Bearer "+token);
        if(body==null)b.GET(); else b.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode post(Identity who,String body) throws Exception {
        var r=request("POST","/ai/analytics",who.token(),body);
        assertEquals(200,r.statusCode(),r.body()); assertTrue(r.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        return json.readTree(r.body());
    }
    @Test void scopedNumbersMatchOriginalSummaryDailyAndStationApis() throws Exception {
        var f=seed(); var r=post(f.manager(),body(null,"CREATED")); var report=r.path("result").path("report");
        String query="?from=2026-11-01&to=2026-11-02&dateBasis=CREATED";
        var summary=request("GET","/statistics/orders/summary"+query,f.manager().token(),null);
        var daily=request("GET","/statistics/orders/daily"+query,f.manager().token(),null);
        var load=request("GET","/statistics/stations/load?at="+report.path("collectedAt").asString(),f.manager().token(),null);
        assertEquals(200,summary.statusCode()); assertEquals(200,daily.statusCode()); assertEquals(200,load.statusCode());
        assertEquals(json.readTree(summary.body()),report.path("summary"));
        assertEquals(json.readTree(daily.body()),report.path("daily"));
        assertEquals(json.readTree(load.body()),report.path("stationLoad"));
        assertEquals(20,report.path("summary").path("orders").path("total").asLong());
        assertEquals(0.8,report.path("summary").path("rates").path("successRate").asDouble(),0.00001);
        assertEquals(0.2,r.path("result").path("analysis").path("failureRate").asDouble(),0.00001);
        assertEquals(2,report.path("summary").path("deliveryTime").path("count").asInt());
        assertEquals(300,report.path("summary").path("deliveryTime").path("avgSeconds").asDouble());
        assertEquals(14,report.path("summary").path("deliveryTime").path("excludedOrders").asInt());
        assertEquals("MOCK",r.path("source").asString()); assertFalse(r.path("degraded").asBoolean());
        assertEquals(1,provider.calls.get());
    }
    @Test void providerReceivesBoundedVerifiedEvidenceNotNamesOrPersonalData() throws Exception {
        var f=seed(); post(f.manager(),body(null,"CREATED"));
        var prompt=provider.last.contentParts().toString();
        for(String secret:List.of("PRIVATE","0900000000",f.outside().getId().toString(),f.manager().user().getEmail()))
            assertFalse(prompt.contains(secret),secret);
        assertTrue(prompt.contains("HIGH_FAILURE")); assertTrue(prompt.contains("DELIVERY_OUTCOMES"));
        provider.last.contentParts().forEach(p -> assertTrue(p.length()<4000));
    }
    @Test void invalidStationAndUnassignedManagerReturnEmptyWithoutProvider() throws Exception {
        var f=seed();
        for(var r:List.of(post(f.manager(),body(f.outside().getId(),"CREATED")),
                post(f.manager(),body(UUID.randomUUID(),"CREATED")),
                post(identity(RoleName.LOGISTICS_MANAGER),body(null,"CREATED")))) {
            assertEquals(0,r.path("result").path("report").path("summary").path("orders").path("total").asInt());
            assertTrue(r.path("result").path("report").path("stationLoad").isEmpty());
            assertEquals("NO_ORDERS_IN_SCOPE",r.path("fallbackReason").asString());
        }
        assertEquals(0,provider.calls.get());
    }
    @Test void completedFilterUsesCompletionHistoryAndSingleDayWarns() throws Exception {
        var f=seed(); var r=post(f.manager(),body(null,"COMPLETED"));
        assertEquals(2,r.path("result").path("report").path("summary").path("orders").path("total").asInt());
        assertEquals(0,r.path("result").path("analysis").path("failureRate").asDouble());
        var day=post(f.manager(),"{\"from\":\"2026-11-02\",\"to\":\"2026-11-02\"}");
        assertTrue(day.path("result").path("analysis").path("trend").isNull());
    }
    @Test void rolesAndUnauthenticatedRequestsAreRejectedBeforeProvider() throws Exception {
        assertEquals(401,request("POST","/ai/analytics",null,"{}").statusCode());
        for(var role:RoleName.values()) if(role!=RoleName.LOGISTICS_MANAGER)
            assertEquals(403,request("POST","/ai/analytics",identity(role).token(),"{}").statusCode(),role.name());
        assertEquals(0,provider.calls.get());
    }
    @Test void malformedRangesAreRejectedBeforeProvider() throws Exception {
        var m=identity(RoleName.LOGISTICS_MANAGER);
        for(String b:List.of("{\"from\":\"2026-11-02\",\"to\":\"2026-11-01\"}",
                "{\"from\":\"2024-01-01\",\"to\":\"2026-11-01\"}","{\"dateBasis\":\"UNKNOWN\"}",
                "{\"from\":\"not-a-date\"}","null"))
            assertEquals(400,request("POST","/ai/analytics",m.token(),b).statusCode(),b);
        assertEquals(0,provider.calls.get());
    }
    @Test void timeoutReturnsReportAndDoesNotMutateOrders() throws Exception {
        var f=seed(); long before=orders.count(); provider.failure=new ApiException(ErrorCode.AI_TIMEOUT);
        var r=post(f.manager(),body(null,"CREATED"));
        assertEquals("FALLBACK",r.path("source").asString()); assertEquals("AI_TIMEOUT",r.path("fallbackReason").asString());
        assertEquals(20,r.path("result").path("report").path("summary").path("orders").path("total").asInt());
        assertEquals(before,orders.count());
    }
    @Test void hallucinatedEvidenceFailsClosedButArbitraryExtraFieldsCannotEscape() throws Exception {
        var f=seed();
        provider.text="{\"insights\":[\"999 orders because weather\"],\"recommendations\":[]}";
        var bad=post(f.manager(),body(null,"CREATED"));
        assertEquals("AI_RESPONSE_INVALID",bad.path("fallbackReason").asString());
        assertFalse(bad.toString().contains("weather"));
        provider.text="{\"insights\":[\"ORDER_VOLUME\"],\"recommendations\":[],\"action\":\"delete all\",\"total\":999}";
        var good=post(f.manager(),body(null,"CREATED"));
        assertFalse(good.path("degraded").asBoolean()); assertFalse(good.toString().contains("delete all"));
        assertEquals(20,good.path("result").path("report").path("summary").path("orders").path("total").asInt());
    }
    @Test void stationFilterAndPermissionRevocationApplyOnEveryRequest() throws Exception {
        var f=seed(); var r=post(f.manager(),body(f.a().getId(),"CREATED"));
        assertEquals(1,r.path("result").path("report").path("stationLoad").size());
        assignments.deleteAll(); // isolated ops75-test H2 fixture database only
        provider.calls.set(0);
        var empty=post(f.manager(),body(null,"CREATED"));
        assertEquals("NO_ORDERS_IN_SCOPE",empty.path("fallbackReason").asString());
        assertEquals(0,provider.calls.get());
    }
}
