package com.smartdrone.application.scheduling;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.domain.entity.DeliveryAttempt;
import com.smartdrone.domain.entity.Station;
import com.smartdrone.domain.enums.StationStatus;
import com.smartdrone.infrastructure.persistence.DeliveryAttemptRepository;
import com.smartdrone.infrastructure.persistence.StationRepository;

// 4.2: cho web biết mỗi slot trong ngày còn bao nhiêu chỗ ở một trạm.
// "date" hiểu theo giờ Việt Nam; slotStart trả về là thời điểm UTC, cùng mốc với API lên lịch.
@Service
public class StationAvailabilityService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final StationRepository stations;
    private final DeliveryAttemptRepository attempts;
    private final ScheduleProperties props;
    private final Clock clock;

    public StationAvailabilityService(StationRepository stations, DeliveryAttemptRepository attempts,
                                      ScheduleProperties props, Clock clock) {
        this.stations = stations;
        this.attempts = attempts;
        this.props = props;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('DISPATCHER','STATION_OPERATOR','LOGISTICS_MANAGER')")
    public StationAvailabilityView get(UUID stationId, LocalDate date) {
        Station station = stations.findById(stationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Không tìm thấy trạm"));

        LocalDate day = date != null ? date : LocalDate.now(clock.withZone(ZONE));
        Instant dayStart = day.atStartOfDay(ZONE).toInstant();
        Instant dayEnd = day.plusDays(1).atStartOfDay(ZONE).toInstant();

        List<DeliveryAttempt> active = attempts.findActiveOverlapping(stationId, dayStart, dayEnd);

        Instant now = Instant.now(clock);
        Instant earliest = now.plus(props.minLead());
        Instant latest = now.plus(props.maxAhead());
        boolean stationActive = station.getStatus() == StationStatus.ACTIVE;

        // Slot nằm trên lưới UTC (cùng luật với API lên lịch), nên bắt đầu từ mốc đầu tiên >= dayStart.
        Duration length = props.slotLength();
        long step = length.toSeconds();
        long first = Math.ceilDiv(dayStart.getEpochSecond(), step) * step;

        List<StationAvailabilityView.Slot> slots = new ArrayList<>();
        for (long t = first; t < dayEnd.getEpochSecond(); t += step) {
            Instant start = Instant.ofEpochSecond(t);
            Instant end = start.plus(length);
            int used = countUsed(active, start, end);
            int free = Math.max(0, station.getCapacity() - used);
            boolean inWindow = !start.isBefore(earliest) && !start.isAfter(latest);
            slots.add(new StationAvailabilityView.Slot(start, end, used, free,
                    stationActive && free > 0 && inWindow));
        }

        return new StationAvailabilityView(station.getId(), station.getName(), station.getStatus(),
                station.getCapacity(), props.slotMinutes(), day, ZONE.getId(), slots);
    }

    private static int countUsed(List<DeliveryAttempt> active, Instant start, Instant end) {
        int used = 0;
        for (DeliveryAttempt a : active) {
            if (a.getSlotStart().isBefore(end) && a.getSlotEnd().isAfter(start)) {
                used++;
            }
        }
        return used;
    }
}