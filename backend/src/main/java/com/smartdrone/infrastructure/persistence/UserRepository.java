package com.smartdrone.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.smartdrone.domain.enums.RoleName;

import com.smartdrone.domain.entity.User;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    @EntityGraph(attributePaths = "role")
    @Query("select u from User u where u.id = :id")
    Optional<User> findWithRoleById(@Param("id") UUID id);

    @Override
    @EntityGraph(attributePaths = "role")
    Page<User> findAll(Pageable pageable);

    // Tìm kiếm/lọc cho trang quản lý tài khoản (nạp sẵn role để tránh N+1).
    @Override
    @EntityGraph(attributePaths = "role")
    Page<User> findAll(Specification<User> spec, Pageable pageable);

    @Query("select count(u) from User u where u.role.name = :name")
    long countByRoleName(@Param("name") RoleName name);

    @Query("select count(u) from User u where u.role.name = :name and u.enabled = true")
    long countActiveByRole(@Param("name") RoleName name);
}
