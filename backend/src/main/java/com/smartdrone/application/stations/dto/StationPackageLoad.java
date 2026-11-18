package com.smartdrone.application.stations.dto;

/** Số kiện đang xử lý của một trạm, chia theo nhóm. */
public record StationPackageLoad(long waitingDispatch, long inbound, long waitingPickup,
                                 long processingTotal) { }