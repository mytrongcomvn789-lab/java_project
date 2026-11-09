package com.smartdrone.application.addresses.dto;

import java.util.List;

public record AddressPage(List<AddressView> items, int page, int pageSize, long total) { }
