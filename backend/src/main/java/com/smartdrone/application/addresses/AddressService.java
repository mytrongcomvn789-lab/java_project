package com.smartdrone.application.addresses;

import java.time.Clock;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.smartdrone.application.addresses.dto.*;
import com.smartdrone.application.common.*;
import com.smartdrone.domain.entity.Address;
import com.smartdrone.infrastructure.persistence.*;

@Service
@PreAuthorize("hasRole('CUSTOMER')")
public class AddressService {
    private final AddressRepository addresses;
    private final UserRepository users;
    private final Clock clock;

    public AddressService(AddressRepository addresses, UserRepository users, Clock clock) {
        this.addresses = addresses; this.users = users; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AddressPage list(UUID ownerId, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "page phải từ 1; pageSize từ 1 đến 100");
        }
        var result = addresses.findByOwnerIdAndDeletedFalse(ownerId,
                PageRequest.of(page - 1, pageSize, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))));
        return new AddressPage(result.map(AddressView::from).getContent(), page, pageSize, result.getTotalElements());
    }

    @Transactional
    public AddressView create(UUID ownerId, AddressInput input) {
        var owner = users.findById(ownerId).orElseThrow(AddressService::notFound);
        var address = new Address(owner, clock.instant());
        apply(address, input);
        return AddressView.from(addresses.saveAndFlush(address));
    }

    @Transactional
    public AddressView update(UUID ownerId, UUID addressId, AddressInput input) {
        var address = addresses.findActiveForUpdate(addressId, ownerId).orElseThrow(AddressService::notFound);
        apply(address, input);
        return AddressView.from(addresses.saveAndFlush(address));
    }

    @Transactional
    public void delete(UUID ownerId, UUID addressId) {
        var address = addresses.findActiveForUpdate(addressId, ownerId).orElseThrow(AddressService::notFound);
        address.archive(clock.instant());
        addresses.saveAndFlush(address);
    }

    /** Task 3.6 must persist this detached value in the order transaction (not a live entity link). */
    @Transactional(readOnly = true)
    public AddressView snapshotForOrder(UUID ownerId, UUID addressId) {
        return AddressView.from(addresses.findByIdAndOwnerIdAndDeletedFalse(addressId, ownerId)
                .orElseThrow(AddressService::notFound));
    }

    private void apply(Address address, AddressInput input) {
        address.update(input.label(), input.recipientName(), input.phone(), input.addressLine(),
                input.latitude(), input.longitude(), clock.instant());
    }
    private static ApiException notFound() { return new ApiException(ErrorCode.RESOURCE_NOT_FOUND); }
}
