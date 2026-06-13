package com.wms.shuttle.model;

import com.influxdb.annotations.Column;
import com.influxdb.annotations.Measurement;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Measurement(name = "shuttle_telemetry")
public class ShuttleDataPoint {

    @Column(tag = true)
    private String shuttleId;

    @Column(tag = true)
    private Integer level;

    @Column(tag = true)
    private Integer aisle;

    @Column
    private Double x;

    @Column
    private Double y;

    @Column
    private Double z;

    @Column
    private Double batteryLevel;

    @Column
    private Boolean hasLoad;

    @Column
    private Double speed;

    @Column
    private String status;

    @Column
    private Double currentLoad;

    @Column
    private Boolean hasFault;

    @Column
    private String faultType;

    @Column
    private Double faultSeverity;

    @Column
    private Double collisionValue;

    @Column(timestamp = true)
    private Instant timestamp;
}
