package com.smartdrone.application.customers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.admin.AuditService;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.UserRepository;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.persistence.criteria.Predicate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Service
@PreAuthorize("hasRole('DISPATCHER')")
public class CustomerDirectoryService {
    private final UserRepository users;
    private final AuditService audit;

    public CustomerDirectoryService(UserRepository users, AuditService audit) {
        this.users = users;
        this.audit = audit;
    }

    public record CustomerView(UUID id, String email, String fullName, String phone,
                               boolean enabled, Instant createdAt) {
        static CustomerView from(User user) {
            return new CustomerView(user.getId(), user.getEmail(), user.getFullName(),
                    user.getPhone(), user.isEnabled(), user.getCreatedAt());
        }
    }

    public record CustomerPage(List<CustomerView> items, int page, int pageSize, long total) { }

    public record UpdateCustomerRequest(
            @NotBlank @Size(max = 150) String fullName,
            @NotNull @Pattern(regexp = "^$|^\\+?[0-9]{9,15}$", message = "Số điện thoại không hợp lệ")
            String phone) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Thuộc tính không được hỗ trợ");
        }
    }

    @Transactional(readOnly = true)
    public CustomerPage list(String search, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "page >= 1; pageSize từ 1 đến 100");
        }
        Specification<User> spec = (root, query, cb) -> {
            List<Predicate> parts = new ArrayList<>();
            parts.add(cb.equal(root.get("role").get("name"), RoleName.CUSTOMER));
            if (search != null && !search.isBlank()) {
                String term = "%" + escapeLike(search.trim().toLowerCase(Locale.ROOT)) + "%";
                parts.add(cb.or(
                        cb.like(cb.lower(root.get("fullName")), term, '\\'),
                        cb.like(cb.lower(root.get("email")), term, '\\'),
                        cb.like(cb.lower(cb.coalesce(root.get("phone"), "")), term, '\\')));
            }
            return cb.and(parts.toArray(Predicate[]::new));
        };
        var result = users.findAll(spec, PageRequest.of(page - 1, pageSize,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))));
        return new CustomerPage(result.map(CustomerView::from).getContent(), page,
                pageSize, result.getTotalElements());
    }

    @Transactional
    public CustomerView update(UUID actorId, UUID customerId, UpdateCustomerRequest request) {
        User customer = users.findWithRoleById(customerId)
                .filter(user -> user.getRole().getName() == RoleName.CUSTOMER)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        String name = request.fullName().trim();
        if (name.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_ERROR, "Họ tên không được để trống");
        customer.setFullName(name);
        String phone = request.phone().trim();
        customer.setPhone(phone.isEmpty() ? null : phone);
        users.saveAndFlush(customer);
        audit.record(actorId, "CUSTOMER_PROFILE_UPDATED", "USER", customerId.toString(),
                "Cập nhật họ tên/số điện thoại; không đổi email hoặc quyền");
        return CustomerView.from(customer);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
