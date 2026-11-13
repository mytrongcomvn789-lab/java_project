package com.smartdrone.application.orders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import com.smartdrone.application.addresses.AddressService;
import com.smartdrone.application.addresses.dto.AddressView;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.orders.dto.OrderCreation;
import com.smartdrone.application.orders.dto.OrderRequest;
import com.smartdrone.application.orders.dto.OrderView;
import com.smartdrone.application.packages.PackageService;
import com.smartdrone.application.packages.dto.PackageInput;
import com.smartdrone.application.packages.dto.PackageView;
import com.smartdrone.application.stations.StationDirectory;
import com.smartdrone.application.workflow.OrderTransitionService;
import com.smartdrone.application.workflow.TransitionCommand;
import com.smartdrone.domain.entity.IdempotencyRecord;
import com.smartdrone.domain.entity.Order;
import com.smartdrone.domain.entity.RecipientSnapshot;
import com.smartdrone.domain.enums.OrderAction;
import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.IdempotencyRecordRepository;
import com.smartdrone.infrastructure.persistence.OrderRepository;

class OrderServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-10T03:00:00Z"), ZoneOffset.UTC);
    private final UUID customer = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID addressId = UUID.randomUUID();
    private final UUID pickup = UUID.randomUUID();
    private final UUID dropoff = UUID.randomUUID();
    private final PackageInput pkgInput = new PackageInput("Hộp", 750, 20.0, 15.0, 8.0, List.of(UUID.randomUUID()));
    private final OrderRequest request = new OrderRequest(pickup, dropoff, addressId, pkgInput);
    private final PackageView pkgView = new PackageView("Hộp", 750, 20.0, 15.0, 8.0, pkgInput.photoFileIds());
    private final AddressView address = new AddressView(addressId, "Nhà", "Nguyễn An", "0901234567",
            "10 Nguyễn Huệ", 10.77, 106.70);

    private OrderRepository orders;
    private AddressService addresses;
    private PackageService packages;
    private StationDirectory stations;
    private OrderTransitionService transitions;
    private IdempotencyRecordRepository idempotency;
    private OrderService service;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        addresses = mock(AddressService.class);
        packages = mock(PackageService.class);
        stations = mock(StationDirectory.class);
        transitions = mock(OrderTransitionService.class);
        idempotency = mock(IdempotencyRecordRepository.class);

        when(stations.isActive(any(UUID.class))).thenReturn(true);
        when(addresses.snapshotForOrder(customer, addressId)).thenReturn(address);
        // Giả lập việc DB cấp id cho đơn mới khi lưu
        when(orders.saveAndFlush(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getId() == null) ReflectionTestUtils.setField(o, "id", orderId);
            return o;
        });
        when(packages.create(eq(customer), eq(orderId), any(PackageInput.class))).thenReturn(pkgView);
        when(packages.replace(eq(customer), eq(orderId), any(PackageInput.class))).thenReturn(pkgView);
        when(packages.findByOrderId(orderId)).thenReturn(Optional.of(pkgView));
        when(idempotency.saveAndFlush(any(IdempotencyRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        service = new OrderService(orders, addresses, packages, stations, transitions, idempotency, clock);
    }

    /** Một đơn đã có trong DB, với trạng thái và version tùy ý. */
    private Order existingOrder(OrderStatus status, long version) {
        Order o = new Order(customer, pickup, dropoff,
                new RecipientSnapshot("Nguyễn An", "0901234567", "10 Nguyễn Huệ", 10.77, 106.70), clock.instant());
        ReflectionTestUtils.setField(o, "id", orderId);
        ReflectionTestUtils.setField(o, "version", version);
        ReflectionTestUtils.setField(o, "status", status);
        return o;
    }

    private ApiException failure(Runnable action) {
        return assertThrows(ApiException.class, action::run);
    }

    // ---------- Tạo đơn ----------

    @Test
    void createSnapshotsAddressSavesOrderThenPackageThenHistoryInOrder() {
        OrderView view = service.create(customer, request);

        InOrder inOrder = inOrder(addresses, orders, packages, transitions);
        inOrder.verify(addresses).snapshotForOrder(customer, addressId);
        inOrder.verify(orders).saveAndFlush(any(Order.class));
        inOrder.verify(packages).create(eq(customer), eq(orderId), any(PackageInput.class));
        inOrder.verify(transitions).recordCreation(any(Order.class), eq(customer), eq(RoleName.CUSTOMER));

        assertEquals(OrderStatus.PENDING, view.status());
        assertEquals(orderId, view.id());
        assertEquals("Nguyễn An", view.recipient().name()); // bản chụp lấy từ địa chỉ

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orders).saveAndFlush(saved.capture());
        assertEquals(customer, saved.getValue().getCustomerId()); // customer lấy từ tham số (JWT), không từ request
    }

    @Test
    void addressOfAnotherCustomerFailsBeforeAnythingIsSaved() {
        when(addresses.snapshotForOrder(customer, addressId)).thenThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND));

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, failure(() -> service.create(customer, request)).getErrorCode());
        verify(orders, never()).saveAndFlush(any(Order.class));
        verify(packages, never()).create(any(), any(), any());
        verify(transitions, never()).recordCreation(any(), any(), any());
    }

    @Test
    void samePickupAndDropoffStationIsRejected() {
        var sameStations = new OrderRequest(pickup, pickup, addressId, pkgInput);

        assertEquals(ErrorCode.VALIDATION_ERROR, failure(() -> service.create(customer, sameStations)).getErrorCode());
        verifyNoInteractions(orders);
    }

    @Test
    void inactiveStationIsRejected() {
        when(stations.isActive(dropoff)).thenReturn(false);

        assertEquals(ErrorCode.VALIDATION_ERROR, failure(() -> service.create(customer, request)).getErrorCode());
        verifyNoInteractions(orders);
    }

    // ---------- Sửa đơn ----------

    @Test
    void updateChangesPendingOrderAndReplacesPackage() {
        Order existing = existingOrder(OrderStatus.PENDING, 3);
        when(orders.findByIdAndCustomerId(orderId, customer)).thenReturn(Optional.of(existing));
        UUID newPickup = UUID.randomUUID(), newDropoff = UUID.randomUUID();

        service.update(customer, orderId, 3, new OrderRequest(newPickup, newDropoff, addressId, pkgInput));

        assertEquals(newPickup, existing.getPickupStationId());
        assertEquals(newDropoff, existing.getDropoffStationId());
        verify(orders).saveAndFlush(existing);
        verify(packages).replace(eq(customer), eq(orderId), any(PackageInput.class));
    }

    @Test
    void updateOfAnotherCustomersOrderIsNotFound() {
        when(orders.findByIdAndCustomerId(orderId, customer)).thenReturn(Optional.empty());

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, failure(() -> service.update(customer, orderId, 0, request)).getErrorCode());
        verify(packages, never()).replace(any(), any(), any());
    }

    @Test
    void updateWithStaleVersionIsConflictAndChangesNothing() {
        Order existing = existingOrder(OrderStatus.PENDING, 3);
        when(orders.findByIdAndCustomerId(orderId, customer)).thenReturn(Optional.of(existing));

        assertEquals(ErrorCode.VERSION_CONFLICT, failure(() -> service.update(customer, orderId, 2, request)).getErrorCode());
        verify(orders, never()).saveAndFlush(any(Order.class));
        verify(packages, never()).replace(any(), any(), any());
    }

    @Test
    void updateOfNonPendingOrderIsBusinessConflict409() {
        when(orders.findByIdAndCustomerId(orderId, customer))
                .thenReturn(Optional.of(existingOrder(OrderStatus.APPROVED, 3)));

        ApiException ex = failure(() -> service.update(customer, orderId, 3, request));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, ex.getErrorCode());
        assertEquals(409, ex.getStatus().value());
        verify(packages, never()).replace(any(), any(), any());
    }

    @Test
    void staleVersionIsReportedBeforeWrongState() {
        // Cả hai cùng sai: version cũ VÀ đơn đã duyệt. Quy ước: báo 412 trước 409.
        when(orders.findByIdAndCustomerId(orderId, customer))
                .thenReturn(Optional.of(existingOrder(OrderStatus.APPROVED, 3)));

        assertEquals(ErrorCode.VERSION_CONFLICT, failure(() -> service.update(customer, orderId, 1, request)).getErrorCode());
    }

    @Test
    void concurrentWriterMakesUpdateFailWithVersionConflictBeforeTouchingPackage() {
        when(orders.findByIdAndCustomerId(orderId, customer)).thenReturn(Optional.of(existingOrder(OrderStatus.PENDING, 3)));
        when(orders.saveAndFlush(any(Order.class))).thenThrow(new ObjectOptimisticLockingFailureException(Order.class, orderId));

        assertEquals(ErrorCode.VERSION_CONFLICT, failure(() -> service.update(customer, orderId, 3, request)).getErrorCode());
        verify(packages, never()).replace(any(), any(), any());
    }

    // ---------- Hủy đơn ----------

    @Test
    void cancelDelegatesToTransitionServiceWithCancelAction() {
        Order existing = existingOrder(OrderStatus.PENDING, 4);
        when(orders.findByIdAndCustomerId(orderId, customer)).thenReturn(Optional.of(existing));

        service.cancel(customer, orderId, 4);

        ArgumentCaptor<TransitionCommand> command = ArgumentCaptor.forClass(TransitionCommand.class);
        verify(transitions).transition(eq(existing), command.capture());
        assertEquals(OrderAction.CANCEL, command.getValue().action());
        assertEquals(RoleName.CUSTOMER, command.getValue().actorRole());
        assertEquals(customer, command.getValue().actorId());
        assertEquals(4, command.getValue().expectedVersion());
    }

    @Test
    void cancelOfAnotherCustomersOrderIsNotFoundAndNeverReachesTransitionService() {
        when(orders.findByIdAndCustomerId(orderId, customer)).thenReturn(Optional.empty());

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, failure(() -> service.cancel(customer, orderId, 0)).getErrorCode());
        verify(transitions, never()).transition(any(), any());
    }

    // ---------- Idempotency ----------

    @Test
    void firstCallClaimsKeyBeforeCreatingOrderThenLinksOrderToKey() {
        when(idempotency.findByUserIdAndIdemKey(customer, "k1")).thenReturn(Optional.empty());

        OrderCreation result = service.createIdempotent(customer, "k1", request);

        assertFalse(result.replayed());
        InOrder inOrder = inOrder(idempotency, orders);
        inOrder.verify(idempotency).saveAndFlush(any(IdempotencyRecord.class)); // chiếm key trước
        inOrder.verify(orders).saveAndFlush(any(Order.class));                  // rồi mới tạo đơn

        ArgumentCaptor<IdempotencyRecord> claim = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(idempotency).saveAndFlush(claim.capture());
        assertEquals(orderId, claim.getValue().getOrderId());
        assertEquals(OrderRequestFingerprint.of(request), claim.getValue().getRequestHash());
    }

    @Test
    void sameKeyAndSameContentReplaysExistingOrderWithoutCreatingAnother() {
        var record = new IdempotencyRecord(customer, "k1", OrderRequestFingerprint.of(request), clock.instant());
        record.complete(orderId);
        when(idempotency.findByUserIdAndIdemKey(customer, "k1")).thenReturn(Optional.of(record));
        when(orders.findByIdAndCustomerId(orderId, customer)).thenReturn(Optional.of(existingOrder(OrderStatus.PENDING, 0)));

        OrderCreation result = service.createIdempotent(customer, "k1", request);

        assertTrue(result.replayed());
        assertEquals(orderId, result.order().id());
        verify(orders, never()).saveAndFlush(any(Order.class));
        verify(packages, never()).create(any(), any(), any());
    }

    @Test
    void sameKeyWithDifferentContentIsConflict() {
        var record = new IdempotencyRecord(customer, "k1", OrderRequestFingerprint.of(request), clock.instant());
        record.complete(orderId);
        when(idempotency.findByUserIdAndIdemKey(customer, "k1")).thenReturn(Optional.of(record));
        var different = new OrderRequest(pickup, dropoff, addressId,
                new PackageInput("Gói khác", 750, 20.0, 15.0, 8.0, pkgInput.photoFileIds()));

        ApiException ex = failure(() -> service.createIdempotent(customer, "k1", different));
        assertEquals(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, ex.getErrorCode());
        assertEquals(409, ex.getStatus().value());
        verify(orders, never()).saveAndFlush(any(Order.class));
    }

    @Test
    void replayChecksCurrentOwnershipBeforeReturningStoredResult() {
        var record = new IdempotencyRecord(customer, "k1", OrderRequestFingerprint.of(request), clock.instant());
        record.complete(orderId);
        when(idempotency.findByUserIdAndIdemKey(customer, "k1")).thenReturn(Optional.of(record));
        when(orders.findByIdAndCustomerId(orderId, customer)).thenReturn(Optional.empty()); // không còn là chủ đơn

        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, failure(() -> service.createIdempotent(customer, "k1", request)).getErrorCode());
    }
}