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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StationStatus status = StationStatus.ACTIVE;

    protected Station() { }

    public Station(String name, double latitude, double longitude, int capacity) {
        update(name, latitude, longitude, capacity);
    }

    public void update(String name, double latitude, double longitude, int capacity) {
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.capacity = capacity;
    }

    public void changeStatus(StationStatus status) {
        this.status = status;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public int getCapacity() { return capacity; }
    public StationStatus getStatus() { return status; }
}
