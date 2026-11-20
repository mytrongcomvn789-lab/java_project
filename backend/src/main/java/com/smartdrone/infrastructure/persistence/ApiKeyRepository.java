package com.smartdrone.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.smartdrone.domain.entity.ApiKey;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
    Page<ApiKey> findAllBy(Pageable pageable);

    // Tên khác findById (đã có sẵn từ JpaRepository) để chỗ gọi thể hiện rõ đây là bước xác thực, không phải CRUD.
    default Optional<ApiKey> findForAuthentication(UUID id) {
        return findById(id);
    }
}