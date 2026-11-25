package com.smartdrone.application.ai.analytics;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import com.smartdrone.application.statistics.*;

/** Read transaction ends before provider I/O. Every query reuses the existing manager station scope. */
@Service
@PreAuthorize("hasRole('LOGISTICS_MANAGER')")
public class OperationsDataReader {
    private final StatisticsService statistics;
    private final Clock clock;
    public OperationsDataReader(StatisticsService statistics, Clock clock) {
        this.statistics = statistics; this.clock = clock;
    }
    @Transactional(readOnly = true)
    public OperationsDataset read(UUID actor, LocalDate from, LocalDate to, DateBasis basis, UUID station) {
        var period = statistics.period(from, to, basis, station);
        var at = clock.instant();
        return new OperationsDataset(statistics.summary(actor, period), statistics.daily(actor, period),
                statistics.stationLoad(actor, station, at), at);
    }
}
