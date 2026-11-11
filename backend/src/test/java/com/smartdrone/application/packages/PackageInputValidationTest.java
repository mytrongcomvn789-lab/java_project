package com.smartdrone.application.packages;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.smartdrone.application.packages.dto.PackageInput;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class PackageInputValidationTest {
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll static void init() { factory = Validation.buildDefaultValidatorFactory(); validator = factory.getValidator(); }
    @AfterAll static void close() { factory.close(); }

    private static PackageInput valid() {
        return new PackageInput("Hộp tai nghe", 800, 20.0, 15.0, 8.0, List.of(UUID.randomUUID()));
    }
    private Set<String> fields(PackageInput in) {
        return validator.validate(in).stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    @Test void validInputHasNoViolations() { assertTrue(validator.validate(valid()).isEmpty()); }

    @Test void descriptionIsTrimmedAndRequired() {
        assertEquals("Hộp", new PackageInput("  Hộp  ", 1, 1.0, 1.0, 1.0, null).description());
        assertTrue(fields(new PackageInput("   ", 1, 1.0, 1.0, 1.0, null)).contains("description"));
        assertTrue(fields(new PackageInput("x".repeat(501), 1, 1.0, 1.0, 1.0, null)).contains("description"));
    }

    @Test void weightAndDimensionsMustBePositive() {
        assertEquals(Set.of("weightGrams"), fields(new PackageInput("a", 0, 1.0, 1.0, 1.0, null)));
        assertEquals(Set.of("lengthCm"), fields(new PackageInput("a", 1, 0.0, 1.0, 1.0, null)));
        assertEquals(Set.of("widthCm"), fields(new PackageInput("a", 1, 1.0, -2.0, 1.0, null)));
        assertEquals(Set.of("heightCm"), fields(new PackageInput("a", 1, 1.0, 1.0, 0.0, null)));
    }

    //kiểm tra trường hợp client quên gửi
    @Test void missingNumbersAreRejected() {
        assertEquals(Set.of("weightGrams", "lengthCm", "widthCm", "heightCm"),
                fields(new PackageInput("a", null, null, null, null, null)));
    }

    @Test void nullPhotoListBecomesEmptyAndNullElementIsRejectedWithout500() {
        assertTrue(valid().photoFileIds().size() == 1);
        assertTrue(new PackageInput("a", 1, 1.0, 1.0, 1.0, null).photoFileIds().isEmpty());
        // List.copyOf sẽ ném NPE ở đây; yêu cầu là trả lỗi validation bình thường.
        var withNull = new PackageInput("a", 1, 1.0, 1.0, 1.0, Arrays.asList(UUID.randomUUID(), null));
        assertEquals(1, validator.validate(withNull).stream().map(ConstraintViolation::getMessage).count());
    }

    @Test void unknownPropertiesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> valid().rejectUnknown("orderId", "x"));
    }
}
