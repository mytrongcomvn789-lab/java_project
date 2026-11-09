package com.smartdrone.application.addresses.dto;

import java.util.UUID;
import com.smartdrone.domain.entity.Address;

public record AddressView(UUID id, String label, String recipientName, String phone,
                          String addressLine, double latitude, double longitude) {
    public static AddressView from(Address address) {
        return new AddressView(address.getId(), address.getLabel(), address.getRecipientName(),
                address.getPhone(), address.getAddressLine(), address.getLatitude(), address.getLongitude());
    }
}
