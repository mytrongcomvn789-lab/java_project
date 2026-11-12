package com.smartdrone.domain.enums;

public enum ChangeSource {
    API,     // người dùng gọi qua endpoint
    SYSTEM   // hệ thống tự động (job định kỳ, v.v.)
}