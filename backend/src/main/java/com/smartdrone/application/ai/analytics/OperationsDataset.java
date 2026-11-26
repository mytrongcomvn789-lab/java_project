package com.smartdrone.application.ai.analytics;

import java.time.Instant;
import java.util.List;
import com.smartdrone.application.statistics.dto.*;

/** Unmodified 5.1 DTOs: counts and current station load have different time semantics. */
public record OperationsDataset(OrderStatsSummary summary, List<DailyOrderStats> daily,
        List<StationLoadStat> stationLoad, Instant collectedAt) { }
