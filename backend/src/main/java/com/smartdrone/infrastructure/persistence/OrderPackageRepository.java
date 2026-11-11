package com.smartdrone.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.smartdrone.domain.entity.OrderPackage;

import jakarta.persistence.LockModeType;

public interface OrderPackageRepository extends JpaRepository<OrderPackage, UUID> {
    boolean existsByOrderId(UUID orderId);

    @EntityGraph(attributePaths = "photoFileIds")
    Optional<OrderPackage> findByOrderId(UUID orderId);

    // Dùng cho danh sách đơn (3.7): một truy vấn cho nhiều đơn, tránh N+1.
    @EntityGraph(attributePaths = "photoFileIds")
    List<OrderPackage> findByOrderIdIn(Collection<UUID> orderIds);

    // Khóa dòng khi sửa để hai lần sửa đồng thời không ghi đè lẫn nhau.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from OrderPackage p where p.orderId = :orderId")
    Optional<OrderPackage> findForUpdateByOrderId(@Param("orderId") UUID orderId);
}
