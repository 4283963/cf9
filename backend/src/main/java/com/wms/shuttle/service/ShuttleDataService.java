package com.wms.shuttle.service;

import com.wms.shuttle.model.ShuttleDataPoint;
import com.wms.shuttle.model.ShuttleStatus;
import com.wms.shuttle.repository.ShuttleRepository;
import com.wms.shuttle.simulation.ShuttleSimulator;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ShuttleDataService {

    private static final Logger log = LoggerFactory.getLogger(ShuttleDataService.class);

    private final ShuttleSimulator simulator;
    private final ShuttleRepository repository;
    private final WebSocketBroadcastService broadcastService;

    @Value("${simulation.enabled:true}")
    private boolean simulationEnabled;

    @Value("${simulation.update-interval-ms:100}")
    private long updateIntervalMs;

    private final AtomicLong lastUpdateTime = new AtomicLong(System.currentTimeMillis());
    private volatile boolean initialized = false;

    public ShuttleDataService(ShuttleSimulator simulator, ShuttleRepository repository,
                              WebSocketBroadcastService broadcastService) {
        this.simulator = simulator;
        this.repository = repository;
        this.broadcastService = broadcastService;
    }

    @PostConstruct
    public void init() {
        if (simulationEnabled) {
            simulator.initialize();
            initialized = true;
            log.info("Shuttle simulator initialized with {} shuttles", simulator.getAllShuttleIds().size());
        }
    }

    @Scheduled(fixedDelayString = "${simulation.update-interval-ms:100}")
    public void updateSimulation() {
        if (!simulationEnabled || !initialized) {
            return;
        }

        long now = System.currentTimeMillis();
        long deltaMs = now - lastUpdateTime.getAndSet(now);

        try {
            List<ShuttleDataPoint> dataPoints = simulator.updateAndGetDataPoints(deltaMs);

            if (!dataPoints.isEmpty()) {
                repository.writeBatch(dataPoints);
                broadcastService.broadcastStatus(simulator.getAllCurrentStatus());
            }
        } catch (Exception e) {
            log.error("Error updating simulation", e);
        }
    }

    public List<ShuttleStatus> getTrajectory(String shuttleId, Instant startTime, Instant endTime) {
        return repository.queryTrajectory(shuttleId, startTime, endTime);
    }

    public List<ShuttleStatus> getTrajectoryLastMinutes(String shuttleId, int minutes) {
        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofMinutes(minutes));
        return repository.queryTrajectory(shuttleId, start, end);
    }

    public List<ShuttleStatus> getAllCurrentStatus() {
        return simulator.getAllCurrentStatus();
    }

    public List<String> getAllShuttleIds() {
        return simulator.getAllShuttleIds().stream().sorted().toList();
    }

    public int getLevels() {
        return simulator.getLevels();
    }

    public int getAisles() {
        return simulator.getAisles();
    }

    public double getTrackLength() {
        return simulator.getTrackLength();
    }

    public double getTrackWidth() {
        return simulator.getTrackWidth();
    }
}
