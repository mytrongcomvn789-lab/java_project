package com.smartdrone.application.scheduling;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.domain.entity.Station;
import com.smartdrone.domain.enums.StationStatus;
import com.smartdrone.infrastructure.persistence.DeliveryAttemptRepository;
import com.smartdrone.infrastructure.persistence.StationRepository;

// Kiểm tra trạm gửi và trạm nhận còn chỗ trong khung giờ. Phải gọi bên trong transaction lên lịch:
// khóa trạm được giữ đến khi transaction đó ghi xong DeliveryAttempt, nên hai người đặt cùng lúc
// không thể cùng chiếm chỗ cuối cùng.
@Service
public class SlotCapacityService {

    private final StationRepository stationRepository;
    private final DeliveryAttemptRepository attemptRepository;

    public SlotCapacityService(StationRepository stationRepository,
                               DeliveryAttemptRepository attemptRepository) {
        this.stationRepository = stationRepository;
        this.attemptRepository = attemptRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockAndCheck(UUID pickupStationId, UUID dropoffStationId, Instant slotStart, Instant slotEnd) {
        // Trạm gửi trùng trạm nhận thì chỉ chiếm 1 chỗ.
        Set<UUID> ids = new LinkedHashSet<>(List.of(pickupStationId, dropoffStationId));
        List<Station> stations = stationRepository.findAllByIdForUpdate(ids);
        if (stations.size() != ids.size()) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy trạm của đơn hàng");
        }
        for (Station station : stations) {
            if (station.getStatus() != StationStatus.ACTIVE) {
                throw new ApiException(ErrorCode.STATION_UNAVAILABLE,
                        "Trạm " + station.getName() + " hiện không hoạt động nên không thể lên lịch");
            }
            long used = attemptRepository.countActiveOverlapping(station.getId(), slotStart, slotEnd);
            if (used >= station.getCapacity()) {
                throw new ApiException(ErrorCode.SLOT_FULL,
                        "Trạm " + station.getName() + " đã đủ chỗ trong khung giờ này ("
                                + used + "/" + station.getCapacity() + ")");
            }
        }
    }
}