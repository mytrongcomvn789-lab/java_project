package com.smartdrone.application.scheduling;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;

// Luật về khung giờ: căn mốc, không quá gần, không quá xa. Không đụng DB nên dễ test.
@Component
public class ScheduleSlotPolicy {

    private final ScheduleProperties props;

    public ScheduleSlotPolicy(ScheduleProperties props) {
        this.props = props;
    }

    public Instant slotEnd(Instant slotStart) {
        return slotStart.plus(props.slotLength());
    }

    /** Ném 400 VALIDATION_ERROR nếu slotStart không hợp lệ. "now" truyền vào để test được. */
    public void validate(Instant slotStart, Instant now) {
        if (slotStart == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Thiếu slotStart");
        }
        long slotSeconds = props.slotLength().toSeconds();
        if (slotStart.getNano() != 0 || slotStart.getEpochSecond() % slotSeconds != 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "slotStart phải trùng mốc đầu khung giờ (mỗi " + props.slotMinutes() + " phút)");
        }
        if (slotStart.isBefore(now.plus(props.minLead()))) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Lịch phải bắt đầu sau hiện tại ít nhất " + props.minLeadMinutes() + " phút");
        }
        if (slotStart.isAfter(now.plus(props.maxAhead()))) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Chỉ được đặt trước tối đa " + props.maxDaysAhead() + " ngày");
        }
    }
}