package com.smartdrone.application.admin.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.RoleName;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// DTO cho /v1/admin/accounts. Không có passwordHash trong bất kỳ DTO trả về nào.
public final class AccountDtos {
    private AccountDtos() { }

    public record AccountView(UUID id, String email, String fullName, String phone,
                              RoleName role, boolean enabled, Instant createdAt) {
        public static AccountView from(User u) {
            return new AccountView(u.getId(), u.getEmail(), u.getFullName(), u.getPhone(),
                    u.getRole().getName(), u.isEnabled(), u.getCreatedAt());
        }
    }

    public record AccountPage(List<AccountView> items, int page, int pageSize, long total) { }

    public record CreateAccountRequest(
            @NotBlank(message = "Họ tên không được để trống")
            @Size(max = 150, message = "Họ tên tối đa 150 ký tự")
            String fullName,

            @NotBlank(message = "Email không được để trống")
            @Email(message = "Email không đúng định dạng")
            @Size(max = 150, message = "Email tối đa 150 ký tự")
            String email,

            @NotBlank(message = "Số điện thoại không được để trống")
            @Pattern(regexp = "^\\+?[0-9]{9,15}$", message = "Số điện thoại không hợp lệ")
            String phone,

            @NotNull(message = "Vui lòng chọn vai trò")
            RoleName role,

            @NotBlank(message = "Mật khẩu không được để trống")
            @Size(min = 8, max = 72, message = "Mật khẩu dài từ 8 đến 72 ký tự")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "Mật khẩu phải có ít nhất 1 chữ cái và 1 chữ số")
            String password) { }

    public record UpdateAccountRequest(
            @NotBlank(message = "Họ tên không được để trống")
            @Size(max = 150, message = "Họ tên tối đa 150 ký tự")
            String fullName,

            @NotBlank(message = "Số điện thoại không được để trống")
            @Pattern(regexp = "^\\+?[0-9]{9,15}$", message = "Số điện thoại không hợp lệ")
            String phone,

            @NotNull(message = "Vui lòng chọn vai trò")
            RoleName role) { }

    public record AccountStatusRequest(
            @NotNull(message = "Thiếu trạng thái enabled")
            Boolean enabled) { }
}
