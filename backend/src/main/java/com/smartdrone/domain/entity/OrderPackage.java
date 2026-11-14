package com.smartdrone.domain.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Nationalized;

// Gói hàng là thành phần của đơn (1 đơn - 1 gói trong v1). order_id là UUID thường, chưa có khóa ngoại
// Ảnh: chỉ lưu fileId, giữ thứ tự do khách sắp xếp.
@Entity
@Table(name = "order_packages",
        uniqueConstraints = @UniqueConstraint(name = "uq_order_packages_order", columnNames = "order_id"))
public class OrderPackage {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;
    @Nationalized
    @Column(nullable = false, length = 500)
    private String description;
    @Column(name = "weight_grams", nullable = false)
    private int weightGrams;
    @Column(name = "length_cm", nullable = false)
    private double lengthCm;
    @Column(name = "width_cm", nullable = false)
    private double widthCm;
    @Column(name = "height_cm", nullable = false)
    private double heightCm;
    @ElementCollection
    @CollectionTable(name = "order_package_photos", joinColumns = @JoinColumn(name = "package_id"))
    @OrderColumn(name = "position")
    @Column(name = "file_id", nullable = false)
    private List<UUID> photoFileIds = new ArrayList<>();
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OrderPackage() { }

    public OrderPackage(UUID orderId, Instant now) {
        this.orderId = orderId;
        this.createdAt = now;
        this.updatedAt = now;
    }
    
    //dùng để đổi nội dung gói hàng
    public void update(String description, int weightGrams, double lengthCm, double widthCm, double heightCm,
                       List<UUID> photoFileIds, Instant now) {
        this.description = description;
        this.weightGrams = weightGrams;
        this.lengthCm = lengthCm;
        this.widthCm = widthCm;
        this.heightCm = heightCm;
        this.photoFileIds.clear();
        this.photoFileIds.addAll(photoFileIds);
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public String getDescription() { return description; }
    public int getWeightGrams() { return weightGrams; }
    public double getLengthCm() { return lengthCm; }
    public double getWidthCm() { return widthCm; }
    public double getHeightCm() { return heightCm; }
    public List<UUID> getPhotoFileIds() { return photoFileIds; }
}
