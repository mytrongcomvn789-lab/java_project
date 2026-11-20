package com.smartdrone.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import com.smartdrone.domain.entity.Role;
import com.smartdrone.domain.enums.RoleName;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByName(RoleName name);

    boolean existsByName(RoleName name);

    @Query("select distinct r from Role r left join fetch r.permissions")
    List<Role> findAllWithPermissions();

    // Tuần tự hóa thay đổi quyền; re-check admin sau khi giữ khóa trong cùng transaction.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Role r where r.name = :name")
    Optional<Role> findForAccessUpdate(@Param("name") RoleName name);
}
