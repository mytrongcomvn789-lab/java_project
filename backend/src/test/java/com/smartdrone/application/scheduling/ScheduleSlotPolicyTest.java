package com.smartdrone.application.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;

class ScheduleSlotPolicyTest {

    // slot 30 phút, đặt sớm nhất sau 30 phút, xa nhất 7 ngày
    private final ScheduleSlotPolicy policy = new ScheduleSlotPolicy(new ScheduleProperties(30, 30, 7));
    private final Instant now = Instant.parse("2026-11-16T03:00:00Z");

    @Test
    void slotDungMocVaTrongKhoangChoPhepThiHopLe() {
        assertThatCode(() -> policy.validate(Instant.parse("2026-11-16T04:00:00Z"), now)).doesNotThrowAnyException();
    }

    @Test
    void thieuSlotStartThiLoiValidation() {
        assertValidationError(null);
    }

    @Test
    void slotLechMocThiLoiValidation() {
        assertValidationError(Instant.parse("2026-11-16T04:10:00Z"));
    }

    @Test
    void slotCoPhanLeGiayThiLoiValidation() {
        assertValidationError(Instant.parse("2026-11-16T04:00:00.500Z"));
    }

    @Test
    void slotQuaGanThiLoiValidation() {
        // 03:00 đúng mốc nhưng chưa đủ 30 phút kể từ now
        assertValidationError(Instant.parse("2026-11-16T03:00:00Z"));
    }

    @Test
    void slotDungBangGioiHanGanNhatThiHopLe() {
        assertThatCode(() -> policy.validate(Instant.parse("2026-11-16T03:30:00Z"), now)).doesNotThrowAnyException();
    }

    @Test
    void slotDungBangGioiHanXaNhatThiHopLe() {
        assertThatCode(() -> policy.validate(Instant.parse("2026-11-23T03:00:00Z"), now)).doesNotThrowAnyException();
    }

    @Test
    void slotQuaXaThiLoiValidation() {
        assertValidationError(Instant.parse("2026-11-23T03:30:00Z"));
    }

    @Test
    void slotEndCongDungDoDaiSlot() {
        Instant start = Instant.parse("2026-11-16T04:00:00Z");
        assertThat(Duration.between(start, policy.slotEnd(start))).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void cauHinhSlotKhongChiaHetMotNgayThiBiTuChoi() {
        assertThatThrownBy(() -> new ScheduleProperties(7, 30, 7)).isInstanceOf(IllegalStateException.class);
    }

    private void assertValidationError(Instant slotStart) {
        assertThatThrownBy(() -> policy.validate(slotStart, now))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }
}