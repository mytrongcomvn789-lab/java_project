
package com.smartdrone.application.stations;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.stations.dto.StationRequest;
import com.smartdrone.application.stations.dto.StationResponse;
import com.smartdrone.domain.entity.Station;
import com.smartdrone.domain.enums.StationStatus;
import com.smartdrone.infrastructure.persistence.StationRepository;

@Service
public class StationService {

    private final StationRepository stationRepository;

    public StationService(StationRepository stationRepository) {
        this.stationRepository = stationRepository;
    }

    // 1. Tạo trạm mới
    @Transactional
    public StationResponse create(StationRequest request) {

        Station station = new Station(
                request.name().trim(),
                request.latitude(),
                request.longitude(),
                request.capacity()
        );

        Station savedStation = stationRepository.save(station);

        return StationResponse.from(savedStation);
    }

    // 2. Lấy danh sách tất cả trạm
    @Transactional(readOnly = true)
    public List<StationResponse> getAll() {

        return stationRepository.findAll()
                .stream()
                .map(StationResponse::from)
                .toList();
    }

    // 3. Cập nhật thông tin trạm
    @Transactional
    public StationResponse update(UUID id, StationRequest request) {

        Station station = findStationById(id);

        station.updateInfo(
                request.name().trim(),
                request.latitude(),
                request.longitude(),
                request.capacity()
        );

        Station updatedStation = stationRepository.save(station);

        return StationResponse.from(updatedStation);
    }

    // 4. Cập nhật trạng thái trạm
    @Transactional
    public StationResponse updateStatus(UUID id, StationStatus status) {

        Station station = findStationById(id);

        if (status == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "Trạng thái trạm không được để trống"
            );
        }

        station.updateStatus(status);

        Station updatedStation = stationRepository.save(station);

        return StationResponse.from(updatedStation);
    }

    // Tìm trạm theo ID
    private Station findStationById(UUID id) {

        return stationRepository.findById(id)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "Không tìm thấy trạm hạ cánh với ID: " + id
                ));
    }
}

