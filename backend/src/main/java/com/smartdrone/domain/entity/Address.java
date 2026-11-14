package com.smartdrone.domain.entity;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;
import org.hibernate.annotations.Nationalized;

@Entity
@Table(name = "addresses", indexes = @Index(name = "ix_addresses_owner_active_created",
        columnList = "user_id,deleted,created_at,id"))
public class Address {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User owner;
    @Nationalized
    @Column(nullable = false, length = 500)
    private String label;
    @Nationalized
    @Column(name = "recipient_name", nullable = false, length = 500)
    private String recipientName;
    @Column(nullable = false, length = 16)
    private String phone;
    @Nationalized
    @Column(name = "address_line", nullable = false, length = 500)
    private String addressLine;
    @Column(nullable = false)
    private double latitude;
    @Column(nullable = false)
    private double longitude;
    @Column(nullable = false)
    private boolean deleted;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Address() { }

    public Address(User owner, Instant now) {
        this.owner = owner;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String label, String recipientName, String phone, String addressLine,
                       double latitude, double longitude, Instant now) {
        this.label = label;
        this.recipientName = recipientName;
        this.phone = phone;
        this.addressLine = addressLine;
        this.latitude = latitude;
        this.longitude = longitude;
        this.updatedAt = now;
    }

    public void archive(Instant now) { deleted = true; updatedAt = now; }
    public UUID getId() { return id; }
    public String getLabel() { return label; }
    public String getRecipientName() { return recipientName; }
    public String getPhone() { return phone; }
    public String getAddressLine() { return addressLine; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
}
