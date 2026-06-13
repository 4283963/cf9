package com.wms.shuttle.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShuttleStatus {
    private String shuttleId;
    private Integer level;
    private Integer aisle;
    private Double x;
    private Double y;
    private Double z;
    private Double batteryLevel;
    private Boolean hasLoad;
    private Double speed;
    private String status;
    private Instant timestamp;
}
