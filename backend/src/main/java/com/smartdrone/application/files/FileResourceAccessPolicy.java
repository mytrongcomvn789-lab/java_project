package com.smartdrone.application.files;

import java.util.UUID;

import com.smartdrone.domain.enums.RoleName;

// Mỗi loại tài nguyên nghiệp vụ (ví dụ "ORDER") đăng ký một bean để quyết định ai được xem tệp đã gắn vào nó.
// VD cho ORDER: CUSTOMER là chủ đơn; STATION_OPERATOR thuộc trạm được giao; DISPATCHER theo phạm vi điều phối.
// Chưa có bean cho resourceType nào thì chỉ người tải lên xem được (từ chối mặc định).
public interface FileResourceAccessPolicy {
    String resourceType();

    boolean canView(UUID actorId, RoleName role, UUID resourceId);
}