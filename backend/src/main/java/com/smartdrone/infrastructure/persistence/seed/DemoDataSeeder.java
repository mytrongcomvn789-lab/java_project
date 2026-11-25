package com.smartdrone.infrastructure.persistence.seed;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.domain.entity.DeliveryAttempt;
import com.smartdrone.domain.entity.OrderPackage;
import com.smartdrone.domain.entity.OrderStatusHistory;
import com.smartdrone.domain.entity.RecipientSnapshot;
import com.smartdrone.domain.entity.StaffStationAssignment;
import com.smartdrone.domain.entity.Station;
import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.ChangeSource;
import com.smartdrone.domain.enums.OrderAction;
import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.DeliveryAttemptRepository;
import com.smartdrone.infrastructure.persistence.OrderPackageRepository;
import com.smartdrone.infrastructure.persistence.OrderRepository;
import com.smartdrone.infrastructure.persistence.OrderStatusHistoryRepository;
import com.smartdrone.infrastructure.persistence.RoleRepository;
import com.smartdrone.infrastructure.persistence.StaffStationAssignmentRepository;
import com.smartdrone.infrastructure.persistence.StationRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

/**
 * Dữ liệu demo cho môi trường DEV: mọi máy trong nhóm có cùng tài khoản, trạm, phân công và đơn mẫu.
 *
 * Chỉ chạy khi app.seed.demo=true (thêm profile "demo"). Mặc định TẮT.
 * Chạy nhiều lần không bị trùng: cái gì đã có thì bỏ qua, không ghi đè.
 * KHÔNG bật ở môi trường thật: các tài khoản demo có mật khẩu công khai.
 */
@Component
@Order(3)
@ConditionalOnProperty(name = "app.seed.demo", havingValue = "true")
public class DemoDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    /** Mật khẩu chung của mọi tài khoản demo (8-72 ký tự, có chữ và số). */
    public static final String DEMO_PASSWORD = "Demo@12345";
    public static final String CUSTOMER_EMAIL = "customer@demo.local";
    public static final String DISPATCHER_EMAIL = "dispatcher@demo.local";
    public static final String OPERATOR_EMAIL = "operator@demo.local";
    public static final String MANAGER_EMAIL = "manager@demo.local";
    public static final String ADMIN_EMAIL = "admin@demo.local";

    private static final String RECIPIENT_PREFIX = "[DEMO] ";

    private final RoleRepository roles;
    private final UserRepository users;
    private final StationRepository stations;
    private final StaffStationAssignmentRepository assignments;
    private final OrderRepository orders;
    private final OrderPackageRepository packages;
    private final OrderStatusHistoryRepository histories;
    private final DeliveryAttemptRepository attempts;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public DemoDataSeeder(RoleRepository roles, UserRepository users, StationRepository stations,
            StaffStationAssignmentRepository assignments, OrderRepository orders,
            OrderPackageRepository packages, OrderStatusHistoryRepository histories,
            DeliveryAttemptRepository attempts, PasswordEncoder passwordEncoder, Clock clock) {
        this.roles = roles;
        this.users = users;
        this.stations = stations;
        this.assignments = assignments;
        this.orders = orders;
        this.packages = packages;
        this.histories = histories;
        this.attempts = attempts;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /** Một đơn mẫu: trạng thái cuối, trạm gửi/nhận (chỉ số trong danh sách trạm), tuổi đơn, lệch khung giờ giao. */
    private record Sample(OrderStatus status, int pickup, int dropoff, int ageHours, Integer slotOffsetMinutes) {}

    private static final List<Sample> SAMPLES = List.of(
            new Sample(OrderStatus.PENDING, 0, 1, 2, null),
            new Sample(OrderStatus.PENDING, 1, 2, 5, null),
            new Sample(OrderStatus.PENDING, 2, 0, 9, null),
            new Sample(OrderStatus.APPROVED, 0, 2, 20, null),
            new Sample(OrderStatus.APPROVED, 3, 1, 26, null),
            new Sample(OrderStatus.REJECTED, 1, 0, 30, null),
            new Sample(OrderStatus.SCHEDULED, 0, 3, 40, 180),
            new Sample(OrderStatus.SCHEDULED, 1, 2, 41, 300),
            new Sample(OrderStatus.PREPARING, 2, 1, 45, 30),
            new Sample(OrderStatus.IN_TRANSIT, 0, 2, 48, -10),
            new Sample(OrderStatus.AWAITING_PICKUP, 1, 0, 50, -40),
            new Sample(OrderStatus.DELIVERED, 0, 1, 72, -1500),
            new Sample(OrderStatus.FAILED, 2, 3, 60, -120),
            new Sample(OrderStatus.RETURNING, 3, 0, 70, -600));

    @Override
    @Transactional
    public void run(String... args) {
        User customer = ensureUser(CUSTOMER_EMAIL, "Khách hàng Demo", RoleName.CUSTOMER);
        User dispatcher = ensureUser(DISPATCHER_EMAIL, "Điều phối viên Demo", RoleName.DISPATCHER);
        User operator = ensureUser(OPERATOR_EMAIL, "Nhân viên trạm Demo", RoleName.STATION_OPERATOR);
        User manager = ensureUser(MANAGER_EMAIL, "Quản lý hậu cần Demo", RoleName.LOGISTICS_MANAGER);
        ensureUser(ADMIN_EMAIL, "Quản trị viên Demo", RoleName.SYSTEM_ADMIN);

        List<Station> demoStations = List.of(
                ensureStation("Trạm Demo A", 10.7769, 106.7009, "1 Lê Lợi, Quận 1, TP.HCM"),
                ensureStation("Trạm Demo B", 10.8505, 106.7717, "2 Võ Văn Ngân, Thủ Đức, TP.HCM"),
                ensureStation("Trạm Demo C", 10.7626, 106.6602, "3 Nguyễn Trãi, Quận 5, TP.HCM"),
                ensureStation("Trạm Demo D", 10.8012, 106.7141, "4 Điện Biên Phủ, Bình Thạnh, TP.HCM"));

        for (User staff : List.of(dispatcher, operator, manager)) {
            for (Station station : demoStations) {
                if (!assignments.existsByStaff_IdAndStation_Id(staff.getId(), station.getId())) {
                    assignments.save(new StaffStationAssignment(staff, station, Instant.now(clock)));
                }
            }
        }

        if (orders.existsByCustomerId(customer.getId())) {
            log.info("Dữ liệu demo: đơn mẫu đã có, bỏ qua.");
        } else {
            createSampleOrders(customer, dispatcher, demoStations);
        }
        log.info("Dữ liệu demo sẵn sàng. Mật khẩu chung các tài khoản *@demo.local: {}", DEMO_PASSWORD);
    }

    private User ensureUser(String email, String fullName, RoleName role) {
        return users.findByEmail(email).orElseGet(() -> {
            log.info("Dữ liệu demo: tạo tài khoản {} ({})", email, role);
            return users.save(new User(email, passwordEncoder.encode(DEMO_PASSWORD), fullName,
                    roles.findByName(role).orElseThrow(() -> new IllegalStateException("Thiếu vai trò " + role))));
        });
    }

    private Station ensureStation(String name, double lat, double lng, String address) {
        return stations.findAll().stream().filter(s -> name.equals(s.getName())).findFirst().orElseGet(() -> {
            log.info("Dữ liệu demo: tạo trạm {}", name);
            return stations.save(new Station(name, lat, lng, 4, address));
        });
    }

    private void createSampleOrders(User customer, User dispatcher, List<Station> st) {
        Instant now = Instant.now(clock);
        int n = 0;
        for (Sample s : SAMPLES) {
            n++;
            Instant created = now.minus(Duration.ofHours(s.ageHours()));
            UUID pickup = st.get(s.pickup()).getId();
            UUID dropoff = st.get(s.dropoff()).getId();
            var order = orders.save(new com.smartdrone.domain.entity.Order(customer.getId(), pickup, dropoff,
                    new RecipientSnapshot(RECIPIENT_PREFIX + "Người nhận " + n, String.format("09012345%02d", n),
                            "Số " + n + " Nguyễn Huệ, Quận 1, TP.HCM", 10.7769, 106.7009), created));

            OrderPackage pkg = new OrderPackage(order.getId(), created);
            pkg.update("Hộp thuốc / tài liệu #" + n, 500 + n * 100, 20, 15, 10, new ArrayList<>(), created);
            packages.save(pkg);

            histories.save(history(order.getId(), 0, null, OrderStatus.PENDING, null, customer.getId(),
                    RoleName.CUSTOMER, null, null, created));

            long version = 0;
            Instant t = created.plus(Duration.ofHours(1));
            if (s.status() == OrderStatus.REJECTED) {
                histories.save(history(order.getId(), ++version, OrderStatus.PENDING, OrderStatus.REJECTED,
                        OrderAction.REJECT, dispatcher.getId(), RoleName.DISPATCHER,
                        "Địa chỉ nhận chưa rõ ràng", null, t));
            } else if (s.status() != OrderStatus.PENDING) {
                histories.save(history(order.getId(), ++version, OrderStatus.PENDING, OrderStatus.APPROVED,
                        OrderAction.APPROVE, dispatcher.getId(), RoleName.DISPATCHER, null, null, t));
            }

            if (s.slotOffsetMinutes() != null) {
                Instant slotStart = now.plus(Duration.ofMinutes(s.slotOffsetMinutes()));
                Instant scheduledAt = created.plus(Duration.ofHours(2));
                DeliveryAttempt attempt = new DeliveryAttempt(order.getId(), 1, pickup, dropoff, slotStart,
                        slotStart.plus(Duration.ofMinutes(30)), dispatcher.getId(), scheduledAt);
                if (s.status() == OrderStatus.RETURNING) {
                    attempt.release(now);
                }
                UUID attemptId = attempts.save(attempt).getId();
                histories.save(history(order.getId(), ++version, OrderStatus.APPROVED, OrderStatus.SCHEDULED,
                        OrderAction.SCHEDULE, dispatcher.getId(), RoleName.DISPATCHER, null, attemptId, scheduledAt));
            }

            if (s.status() != OrderStatus.PENDING) {
                order.changeStatus(s.status());
                orders.save(order);
            }
        }
        log.info("Dữ liệu demo: đã tạo {} đơn mẫu.", SAMPLES.size());
    }

    private OrderStatusHistory history(UUID orderId, long version, OrderStatus from, OrderStatus to,
            OrderAction action, UUID actorId, RoleName actorRole, String reason, UUID attemptId, Instant at) {
        return new OrderStatusHistory(orderId, version, from, to, action, actorId, actorRole, ChangeSource.API,
                reason, attemptId, UUID.randomUUID().toString(), at);
    }
}
