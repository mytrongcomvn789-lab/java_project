package com.smartdrone.infrastructure.persistence.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import com.smartdrone.domain.entity.Role;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.RoleRepository;

// Chạy mỗi lần khởi động: thêm 5 vai trò nếu chưa có (chạy nhiều lần không bị trùng).
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final RoleRepository roleRepository;

    public DataSeeder(RoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    @Override
    public void run(String... args) {
        for (RoleName name : RoleName.values()) {
            if (!roleRepository.existsByName(name)) {
                roleRepository.save(new Role(name));
                log.info("Đã thêm vai trò: {}", name);
            }
        }
    }
}
