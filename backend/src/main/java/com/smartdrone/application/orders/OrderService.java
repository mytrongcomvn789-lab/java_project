package com.smartdrone.application.orders;

import java.time.Clock;
import java.util.Optional;                                                   
import java.util.UUID;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.addresses.AddressService;
import com.smartdrone.application.addresses.dto.AddressView;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.orders.dto.OrderCreation;                     
import com.smartdrone.application.orders.dto.OrderRequest;
import com.smartdrone.application.orders.dto.OrderView;
import com.smartdrone.application.packages.PackageService;
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

@Service
public class OrderService {

    private final OrderRepository orders;
    private final AddressService addresses;
    private final PackageService packages;
    private final StationDirectory stations;
    private final OrderTransitionService transitions;
    private final IdempotencyRecordRepository idempotency;                      
    private final Clock clock;

    public OrderService(OrderRepository orders, AddressService addresses, PackageService packages,
                        StationDirectory stations, OrderTransitionService transitions,
                        IdempotencyRecordRepository idempotency,           
                        Clock clock) {
        this.orders = orders;
        this.addresses = addresses;
        this.packages = packages;
        this.stations = stations;
        this.transitions = transitions;
        this.idempotency = idempotency;                                       
        this.clock = clock;
    }

    // ================= TẠO ĐƠN =================

    /** Tạo đơn không cần Idempotency-Key (giữ lại cho test và nơi gọi nội bộ). */
    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional
    public OrderView create(UUID customerId, OrderRequest request) {
        return doCreate(customerId, request);                                
    }

    // phần thân của create được tách ra đây để create và createIdempotent dùng chung
    private OrderView doCreate(UUID customerId, OrderRequest request) {
        RecipientSnapshot recipient = prepare(customerId, request);

        Order order = orders.saveAndFlush(new Order(customerId, request.pickupStationId(),
                request.dropoffStationId(), recipient, clock.instant()));
        PackageView pkg = packages.create(customerId, order.getId(), request.packageInfo());
        transitions.recordCreation(order, customerId, RoleName.CUSTOMER);

        return OrderView.from(order, pkg);
    }

    // tạo đơn có Idempotency-Key
    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional
    public OrderCreation createIdempotent(UUID customerId, String key, OrderRequest request) {
        String hash = OrderRequestFingerprint.of(request);

        // 1. Key này đã dùng rồi? Nếu có thì trả lại đơn cũ (hoặc báo 409 nếu nội dung khác)
        Optional<OrderCreation> replay = findReplay(customerId, key, hash);
        if (replay.isPresent()) return replay.get();

        // 2. Chưa dùng: "chiếm" key trước khi làm việc nặng
        IdempotencyRecord claim = idempotency.saveAndFlush(
                new IdempotencyRecord(customerId, key, hash, clock.instant()));

        // 3. Tạo đơn, rồi ghi lại "key này ứng với đơn nào"
        OrderView created = doCreate(customerId, request);
        claim.complete(created.id());
        return new OrderCreation(created, false);
    }

    // dùng khi hai request cùng key chạy song song, bên thua đọc lại kết quả bên thắng
    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(readOnly = true)
    public Optional<OrderCreation> replay(UUID customerId, String key, OrderRequest request) {
        return findReplay(customerId, key, OrderRequestFingerprint.of(request));
    }

    
    private Optional<OrderCreation> findReplay(UUID customerId, String key, String hash) {
        return idempotency.findByUserIdAndIdemKey(customerId, key).map(record -> {
            if (!record.getRequestHash().equals(hash)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);     // cùng key, khác nội dung
            }
            Order order = load(customerId, record.getOrderId());                // vẫn phải là chủ đơn, không thì 404
            return new OrderCreation(view(order), true);
        });
    }

    // ================= XEM / SỬA / HỦY  =================

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(readOnly = true)
    public OrderView getOwn(UUID customerId, UUID orderId) {
        return view(load(customerId, orderId));
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional
    public OrderView update(UUID customerId, UUID orderId, long expectedVersion, OrderRequest request) {
        Order order = load(customerId, orderId);
        if (order.getVersion() != expectedVersion) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT);
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Chỉ sửa được đơn đang chờ duyệt (hiện là " + order.getStatus() + ")");
        }

        RecipientSnapshot recipient = prepare(customerId, request);
        order.updateDetails(request.pickupStationId(), request.dropoffStationId(), recipient, clock.instant());
        flushOrder(order);

        PackageView pkg = packages.replace(customerId, order.getId(), request.packageInfo());
        return OrderView.from(order, pkg);
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional
    public OrderView cancel(UUID customerId, UUID orderId, long expectedVersion) {
        Order order = load(customerId, orderId);
        transitions.transition(order, new TransitionCommand(
                OrderAction.CANCEL, customerId, RoleName.CUSTOMER, expectedVersion, null, null, null));
        return view(order);
    }

    // ================= HÀM PHỤ  =================

    private Order load(UUID customerId, UUID orderId) {
        return orders.findByIdAndCustomerId(orderId, customerId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private OrderView view(Order order) {
        return OrderView.from(order, packages.findByOrderId(order.getId()).orElse(null));
    }

    private RecipientSnapshot prepare(UUID customerId, OrderRequest request) {
        packages.validate(request.packageInfo());
        checkRoute(request.pickupStationId(), request.dropoffStationId());
        AddressView address = addresses.snapshotForOrder(customerId, request.recipientAddressId());
        return new RecipientSnapshot(address.recipientName(), address.phone(),
                address.addressLine(), address.latitude(), address.longitude());
    }

    private void flushOrder(Order order) {
        try {
            orders.saveAndFlush(order);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT);
        }
    }

    private void checkRoute(UUID pickup, UUID dropoff) {
        if (pickup.equals(dropoff)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Trạm gửi và trạm nhận phải khác nhau");
        }
        if (!stations.isActive(pickup)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Trạm gửi không tồn tại hoặc không hoạt động");
        }
        if (!stations.isActive(dropoff)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Trạm nhận không tồn tại hoặc không hoạt động");
        }
    }
}