package com.wms.shuttle.controller;

import com.wms.shuttle.model.ShuttleStatus;
import com.wms.shuttle.service.ShuttleDataService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/shuttles")
@CrossOrigin(origins = "*")
public class ShuttleController {

    private final ShuttleDataService shuttleDataService;

    public ShuttleController(ShuttleDataService shuttleDataService) {
        this.shuttleDataService = shuttleDataService;
    }

    @GetMapping("/status")
    public ResponseEntity<List<ShuttleStatus>> getAllCurrentStatus() {
        return ResponseEntity.ok(shuttleDataService.getAllCurrentStatus());
    }

    @GetMapping("/ids")
    public ResponseEntity<List<String>> getAllShuttleIds() {
        return ResponseEntity.ok(shuttleDataService.getAllShuttleIds());
    }

    @GetMapping("/trajectory/{shuttleId}")
    public ResponseEntity<List<ShuttleStatus>> getTrajectory(
            @PathVariable String shuttleId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime,
            @RequestParam(defaultValue = "5000") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50000));
        List<ShuttleStatus> trajectory = shuttleDataService.getTrajectory(shuttleId, startTime, endTime, safeLimit);
        return ResponseEntity.ok(trajectory);
    }

    @GetMapping("/trajectory/{shuttleId}/last")
    public ResponseEntity<List<ShuttleStatus>> getTrajectoryLast(
            @PathVariable String shuttleId,
            @RequestParam(defaultValue = "10") int minutes) {
        int safeMinutes = Math.max(1, Math.min(minutes, 120));
        List<ShuttleStatus> trajectory = shuttleDataService.getTrajectoryLastMinutes(shuttleId, safeMinutes);
        return ResponseEntity.ok(trajectory);
    }

    @GetMapping("/warehouse/config")
    public ResponseEntity<Map<String, Object>> getWarehouseConfig() {
        return ResponseEntity.ok(Map.of(
                "levels", shuttleDataService.getLevels(),
                "aisles", shuttleDataService.getAisles(),
                "trackLength", shuttleDataService.getTrackLength(),
                "trackWidth", shuttleDataService.getTrackWidth()
        ));
    }
}
