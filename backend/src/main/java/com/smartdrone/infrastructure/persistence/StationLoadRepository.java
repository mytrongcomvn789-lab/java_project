package com.smartdrone.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.smartdrone.application.stations.dto.StationPackageCount;
import com.smartdrone.domain.entity.OrderPackage;
import com.smartdrone.domain.enums.OrderStatus;

/** 4.6: đếm số kiện theo trạm. Chỉ đọc; viết bằng JPQL nên chạy được trên cả H2 lẫn SQL Server. */
public interface StationLoadRepository extends Repository<OrderPackage, UUID> {

    /** Đếm kiện theo TRẠM GỬI, chỉ tính các đơn có trạng thái nằm trong danh sách. */
    @Query("""
            select new com.smartdrone.application.stations.dto.StationPackageCount(o.pickupStationId, count(p))
            from Order o, OrderPackage p
            where p.orderId = o.id and o.status in :statuses
            group by o.pickupStationId
            """)
    List<StationPackageCount> countByPickupStation(@Param("statuses") Collection<OrderStatus> statuses);

    /** Đếm kiện theo TRẠM NHẬN, chỉ tính các đơn có trạng thái nằm trong danh sách. */
    @Query("""
            select new com.smartdrone.application.stations.dto.StationPackageCount(o.dropoffStationId, count(p))
            from Order o, OrderPackage p
            where p.orderId = o.id and o.status in :statuses
            group by o.dropoffStationId
            """)
    List<StationPackageCount> countByDropoffStation(@Param("statuses") Collection<OrderStatus> statuses);
}