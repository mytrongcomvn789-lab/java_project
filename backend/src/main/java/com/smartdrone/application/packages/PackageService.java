package com.smartdrone.application.packages;

import java.time.Clock;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.files.FileService;
import com.smartdrone.application.packages.dto.PackageInput;
import com.smartdrone.application.packages.dto.PackageView;
import com.smartdrone.domain.entity.OrderPackage;
import com.smartdrone.domain.enums.FilePurpose;
import com.smartdrone.infrastructure.persistence.OrderPackageRepository;


@Service
public class PackageService {
    /** resourceType dùng khi gắn ảnh; khớp ví dụ ORDER trong docs/05-api/files-api.md. */
    public static final String RESOURCE_TYPE = "ORDER";

    private final OrderPackageRepository packages;
    private final FileService files;
    private final PackageProperties limits;
    private final Clock clock;

    public PackageService(OrderPackageRepository packages, FileService files, PackageProperties limits, Clock clock) {
        this.packages = packages;
        this.files = files;
        this.limits = limits;
        this.clock = clock;
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional
    public PackageView create(UUID customerId, UUID orderId, PackageInput input) {
        validate(input); //kiểm tra giới hạn
        if (packages.existsByOrderId(orderId)) throw alreadyExists();
        attachPhotos(customerId, orderId, input.photoFileIds());
        var pkg = new OrderPackage(orderId, clock.instant());
        apply(pkg, input);
        try {
            return PackageView.from(packages.saveAndFlush(pkg));
        } catch (DataIntegrityViolationException e) { // hai request tạo cùng lúc: ràng buộc unique order_id
            throw alreadyExists();
        }
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional
    public PackageView replace(UUID customerId, UUID orderId, PackageInput input) {
        validate(input);
        var pkg = packages.findForUpdateByOrderId(orderId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        attachPhotos(customerId, orderId, input.photoFileIds());
        apply(pkg, input);
        return PackageView.from(packages.saveAndFlush(pkg));
    }

    @Transactional(readOnly = true)
    public Optional<PackageView> findByOrderId(UUID orderId) {
        return packages.findByOrderId(orderId).map(PackageView::from);
    }

    /** Một truy vấn cho cả trang danh sách đơn. Đơn không có gói thì không có khóa trong kết quả. */
    @Transactional(readOnly = true)
    public Map<UUID, PackageView> findByOrderIds(Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) return Map.of();
        var result = new LinkedHashMap<UUID, PackageView>();
        packages.findByOrderIdIn(orderIds).forEach(p -> result.put(p.getOrderId(), PackageView.from(p)));
        return result;
    }

    /** Kiểm tra giới hạn do nhóm chốt (cấu hình). Công khai để 3.6 có thể kiểm tra sớm trước khi lưu đơn. */
    public void validate(PackageInput input) {
        check(input.weightGrams() <= limits.maxWeightGrams(),
                "Trọng lượng tối đa " + limits.maxWeightGrams() + " g");
        check(input.lengthCm() <= limits.maxLengthCm(), "Chiều dài tối đa " + limits.maxLengthCm() + " cm");
        check(input.widthCm() <= limits.maxWidthCm(), "Chiều rộng tối đa " + limits.maxWidthCm() + " cm");
        check(input.heightCm() <= limits.maxHeightCm(), "Chiều cao tối đa " + limits.maxHeightCm() + " cm");
        List<UUID> photos = input.photoFileIds();
        check(photos.size() >= limits.minPhotos(), "Cần ít nhất " + limits.minPhotos() + " ảnh gói hàng");
        check(photos.size() <= limits.maxPhotos(), "Tối đa " + limits.maxPhotos() + " ảnh gói hàng");
        check(new HashSet<>(photos).size() == photos.size(), "Danh sách ảnh có ảnh bị trùng");
    }

    // Gắn lặp lại cùng tài nguyên là an toàn (FileService.attach idempotent), nên replace gọi lại cho ảnh cũ được.
    // Ảnh bị gỡ khỏi danh sách vẫn ở trạng thái ATTACHED vì FileService chưa có thao tác gỡ (xem docs).
    private void attachPhotos(UUID customerId, UUID orderId, List<UUID> photoFileIds) {
        for (UUID fileId : photoFileIds) {
            files.attach(customerId, fileId, FilePurpose.PACKAGE_PHOTO, RESOURCE_TYPE, orderId);
        }
    }

    private void apply(OrderPackage pkg, PackageInput in) {
        pkg.update(in.description(), in.weightGrams(), in.lengthCm(), in.widthCm(), in.heightCm(),
                in.photoFileIds(), clock.instant());
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new ApiException(ErrorCode.VALIDATION_ERROR, message);
    }

    private static ApiException alreadyExists() {
        return new ApiException(HttpStatus.CONFLICT, "Đơn hàng đã có thông tin gói hàng");
    }
}
