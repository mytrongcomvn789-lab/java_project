package com.smartdrone.domain.workflow;

import static com.smartdrone.domain.enums.OrderAction.*;
import static com.smartdrone.domain.enums.OrderStatus.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.domain.enums.OrderAction;
import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.enums.RoleName;

class TransitionRulesTest {

    record Edge(OrderStatus from, OrderAction action, OrderStatus to) { }

    // Sơ đồ theo hợp đồng APDDMP-9, viết tay, KHÔNG lấy từ TransitionRules.
    private static final List<Edge> CONTRACT = List.of(
            new Edge(PENDING, APPROVE, APPROVED),
            new Edge(PENDING, REJECT, REJECTED),
            new Edge(PENDING, CANCEL, CANCELLED),
            new Edge(APPROVED, SCHEDULE, SCHEDULED),
            new Edge(SCHEDULED, RECEIVE, PREPARING),
            new Edge(PREPARING, DISPATCH, IN_TRANSIT),
            new Edge(IN_TRANSIT, ARRIVE, AWAITING_PICKUP),
            new Edge(AWAITING_PICKUP, CONFIRM, DELIVERED),
            new Edge(SCHEDULED, FAIL, FAILED),
            new Edge(PREPARING, FAIL, FAILED),
            new Edge(IN_TRANSIT, FAIL, FAILED),
            new Edge(AWAITING_PICKUP, FAIL, FAILED),
            new Edge(FAILED, SCHEDULE, SCHEDULED),     // retry: lần thử mới
            new Edge(FAILED, START_RETURN, RETURNING),
            new Edge(RETURNING, COMPLETE_RETURN, RETURNED));

    private static final Set<RequiredField> ALL_FIELDS = EnumSet.allOf(RequiredField.class);
    private static final Set<RequiredField> NO_FIELDS = EnumSet.noneOf(RequiredField.class);

    private static ErrorCode errorOf(OrderStatus from, OrderAction action, RoleName role,
                                     Set<RequiredField> provided) {
        return assertThrows(ApiException.class,
                () -> TransitionRules.validate(from, action, role, provided)).getErrorCode();
    }

    // ---- Các chuyển hợp lệ ----

    @Test
    void bangLuatCoDungVaDuNhungCanhTrongHopDong() {
        assertEquals(CONTRACT.size(), TransitionRules.all().size());
        for (Edge e : CONTRACT) {
            TransitionRule rule = TransitionRules.find(e.from(), e.action())
                    .orElseThrow(() -> new AssertionError("Thiếu cạnh " + e));
            assertEquals(e.to(), rule.to(), "Sai trạng thái đích của " + e);
        }
    }

    @Test
    void moiRuleDayDuVaCoSuKien() {
        for (TransitionRule r : TransitionRules.all()) {
            assertFalse(r.roles().isEmpty(), "Cạnh không có role: " + r);
            assertNotNull(r.event(), "Cạnh không có sự kiện: " + r);
        }
    }

    // ---- Các chuyển không hợp lệ ----

    @Test
    void moiToHopKhongCoTrongHopDongDeuBiTuChoiKeCaVoiQuanTriVien() {
        Set<String> allowed = CONTRACT.stream()
                .map(e -> e.from() + ":" + e.action()).collect(Collectors.toSet());
        int checked = 0;
        for (OrderStatus s : OrderStatus.values()) {
            for (OrderAction a : OrderAction.values()) {
                if (allowed.contains(s + ":" + a)) continue;
                checked++;
                assertTrue(TransitionRules.find(s, a).isEmpty(), "Cạnh thừa: " + s + " " + a);
                assertEquals(ErrorCode.INVALID_STATE_TRANSITION,
                        errorOf(s, a, RoleName.SYSTEM_ADMIN, ALL_FIELDS), s + " " + a);
            }
        }
        assertEquals(OrderStatus.values().length * OrderAction.values().length - CONTRACT.size(), checked);
    }

    @Test
    void khongTheNhayTuPendingThangToiDelivered() {
        assertTrue(TransitionRules.all().stream()
                .noneMatch(r -> r.from() == PENDING && r.to() == DELIVERED));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION,
                errorOf(PENDING, CONFIRM, RoleName.STATION_OPERATOR, ALL_FIELDS));
    }

    @Test
    void denTramChuaPhaiDaGiao() {
        assertEquals(AWAITING_PICKUP, TransitionRules.find(IN_TRANSIT, ARRIVE).orElseThrow().to());
        // DELIVERED chỉ đạt được từ AWAITING_PICKUP, và chỉ qua CONFIRM
        assertTrue(TransitionRules.all().stream()
                .filter(r -> r.to() == DELIVERED)
                .allMatch(r -> r.from() == AWAITING_PICKUP && r.action() == CONFIRM));
    }

    // ---- Trạng thái kết thúc ----

    @Test
    void trangThaiKetThucKhongCoDuongRa() {
        Set<OrderStatus> terminal = Arrays.stream(OrderStatus.values())
                .filter(OrderStatus::isTerminal).collect(Collectors.toSet());
        assertEquals(Set.of(REJECTED, CANCELLED, DELIVERED, RETURNED), terminal);
        for (OrderStatus s : OrderStatus.values()) {
            boolean hasExit = TransitionRules.all().stream().anyMatch(r -> r.from() == s);
            assertEquals(!s.isTerminal(), hasExit, s.name());
        }
    }

    // ---- Vai trò ----

    @Test
    void moiCanhChiChapNhanDungVaiTro() {
        for (TransitionRule r : TransitionRules.all()) {
            for (RoleName role : RoleName.values()) {
                if (r.roles().contains(role)) {
                    assertEquals(r, TransitionRules.validate(r.from(), r.action(), role, ALL_FIELDS));
                } else {
                    assertEquals(ErrorCode.FORBIDDEN,
                            errorOf(r.from(), r.action(), role, ALL_FIELDS),
                            r.from() + " " + r.action() + " " + role);
                }
            }
        }
    }

    @Test
    void khachChiDuocHuyDonKhiConPending() {
        assertEquals(List.of(CANCEL), TransitionRules.allowedActions(PENDING, RoleName.CUSTOMER));
        for (OrderStatus s : OrderStatus.values()) {
            if (s == PENDING) continue;
            assertTrue(TransitionRules.allowedActions(s, RoleName.CUSTOMER).isEmpty(), s.name());
        }
        // đơn đã duyệt: khách hủy bị từ chối vì cạnh không tồn tại
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION,
                errorOf(APPROVED, CANCEL, RoleName.CUSTOMER, NO_FIELDS));
    }

    // ---- Dữ liệu bắt buộc ----

    @Test
    void thieuDuLieuBatBuocThiBiTuChoi() {
        for (TransitionRule r : TransitionRules.all()) {
            RoleName role = r.roles().iterator().next();
            for (RequiredField missing : r.required()) {
                Set<RequiredField> provided = EnumSet.allOf(RequiredField.class);
                provided.remove(missing);
                assertEquals(ErrorCode.VALIDATION_ERROR,
                        errorOf(r.from(), r.action(), role, provided),
                        r.from() + " " + r.action() + " thiếu " + missing);
            }
        }
    }

    // ---- Thất bại và thử lại ----

    @Test
    void chiBonTrangThaiDangXuLyMoiCoTheThatBai() {
        Set<OrderStatus> failFrom = new HashSet<>();
        TransitionRules.all().stream().filter(r -> r.action() == FAIL)
                .forEach(r -> failFrom.add(r.from()));
        assertEquals(Set.of(SCHEDULED, PREPARING, IN_TRANSIT, AWAITING_PICKUP), failFrom);
    }

    @Test
    void retryQuayVeScheduledVaBatBuocCoLanThuMoi() {
        TransitionRule retry = TransitionRules.find(FAILED, SCHEDULE).orElseThrow();
        assertEquals(SCHEDULED, retry.to());
        assertTrue(retry.required().contains(RequiredField.ATTEMPT_ID));
        assertEquals(ErrorCode.VALIDATION_ERROR,
                errorOf(FAILED, SCHEDULE, RoleName.DISPATCHER, NO_FIELDS));
        // từ FAILED chỉ có 2 lối đi: thử lại hoặc hoàn hàng
        assertEquals(Set.of(SCHEDULE, START_RETURN),
                new HashSet<>(TransitionRules.allowedActions(FAILED, RoleName.DISPATCHER)));
    }
}