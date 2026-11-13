package com.smartdrone.application.orders.dto;

import com.smartdrone.domain.entity.RecipientSnapshot;

public record RecipientView(String name, String phone, String addressLine,
                            double latitude, double longitude) {
    public static RecipientView from(RecipientSnapshot r) {
        return new RecipientView(r.getName(), r.getPhone(), r.getAddressLine(),
                r.getLatitude(), r.getLongitude());
    }
}