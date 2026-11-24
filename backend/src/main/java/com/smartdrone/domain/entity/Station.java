package com.smartdrone.domain.entity;

import java.util.UUID;

import com.smartdrone.domain.enums.StationStatus;
import org.hibernate.annotations.Nationalized;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "stations")
public class Station {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Nationalized
    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    @Column(nullable = false)
    private int capacity;

    /** Địa chỉ dễ đọc của trạm; có thể trống với trạm tạo trước khi có cột này. */
    @Nationalized
    @Column(length = 300)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StationStatus status = StationStatus.ACTIVE;

    protected Station() { }

    public Station(String name, double latitude, double longitude, int capacity) {
        this(name, latitude, longitude, capacity, null);
    }

    public Station(String name, double latitude, double longitude, int capacity, String address) {
        this.latitude = latitude;
        this.longitude = longitude;
        update(name, capacity, address);
    }

    /** Tạo trạm chỉ với địa chỉ; tọa độ để 0,0 (theo dõi đơn coi là chưa có vị trí). */
    public Station(String name, int capacity, String address) {
        this(name, 0, 0, capacity, address);
    }

    /** Sửa thông tin hiển thị; không đụng tới tọa độ đã lưu. */
    public void update(String name, int capacity, String address) {
        this.name = name;
        this.capacity = capacity;
        this.address = address;
    }

    public void changeStatus(StationStatus status) {
        this.status = status;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public int getCapacity() { return capacity; }
    public String getAddress() { return address; }
    public StationStatus getStatus() { return status; }
}
