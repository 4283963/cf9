package com.wms.shuttle.simulation;

import com.wms.shuttle.model.ShuttleDataPoint;
import com.wms.shuttle.model.ShuttleStatus;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ShuttleSimulator {

    @Value("${simulation.shuttle-count:50}")
    private int shuttleCount;

    @Value("${simulation.warehouse.aisles:5}")
    private int aisles;

    @Value("${simulation.warehouse.levels:5}")
    private int levels;

    @Value("${simulation.warehouse.track-length:40}")
    private double trackLength;

    @Value("${simulation.warehouse.track-width:20}")
    private double trackWidth;

    private final Map<String, ShuttleState> shuttleStates = new ConcurrentHashMap<>();
    private final Random random = new Random(42);

    private static final double COLLISION_THRESHOLD = 0.7;
    private static final double CURRENT_OVERLOAD_THRESHOLD = 10.0;
    private static final long MIN_FAULT_INTERVAL_MS = 20000;
    private static final long FAULT_RECOVERY_TIME_MS = 8000;
    private static final double FAULT_CHANCE_PER_SECOND = 0.003;

    @Data
    private static class ShuttleState {
        private String shuttleId;
        private int level;
        private int aisle;
        private double x;
        private double z;
        private double targetX;
        private double targetZ;
        private double speed;
        private double maxSpeed;
        private double batteryLevel;
        private boolean hasLoad;
        private String status;
        private Direction direction;
        private long stateChangeTime;
        private long loadChangeTime;
        private boolean initialized;
        private double currentLoad;
        private boolean hasFault;
        private String faultType;
        private double faultSeverity;
        private double collisionValue;
        private long faultStartTime;
        private long lastFaultCheckTime;
        private double fatigueDegradation;
        private double baseCurrent;

        enum Direction {
            X_POS, X_NEG, Z_POS, Z_NEG
        }
    }

    public void initialize() {
        for (int i = 0; i < shuttleCount; i++) {
            String shuttleId = String.format("SHUTTLE-%03d", i + 1);
            ShuttleState state = new ShuttleState();
            state.setShuttleId(shuttleId);
            state.setLevel(i % levels);
            state.setAisle((i / levels) % aisles);
            state.setX(random.nextDouble() * trackLength);
            state.setZ(random.nextDouble() * trackWidth);
            state.setMaxSpeed(1.5 + random.nextDouble() * 1.0);
            state.setSpeed(state.getMaxSpeed());
            state.setBatteryLevel(60 + random.nextDouble() * 40);
            state.setHasLoad(random.nextBoolean());
            state.setStatus("MOVING");
            state.setDirection(ShuttleState.Direction.values()[random.nextInt(4)]);
            state.setInitialized(false);
            state.setBaseCurrent(4.0 + random.nextDouble() * 2.0);
            state.setFatigueDegradation(1.0 + random.nextDouble() * 0.3);
            state.setCurrentLoad(state.getBaseCurrent());
            state.setHasFault(false);
            state.setFaultType(null);
            state.setFaultSeverity(0.0);
            state.setCollisionValue(0.0);
            state.setLastFaultCheckTime(0);
            state.setFaultStartTime(0);
            setNewTarget(state);
            shuttleStates.put(shuttleId, state);
        }
    }

    public List<ShuttleDataPoint> updateAndGetDataPoints(long deltaMs) {
        List<ShuttleDataPoint> points = new ArrayList<>();
        Instant now = Instant.now();

        for (ShuttleState state : shuttleStates.values()) {
            if (!state.isInitialized()) {
                state.setStateChangeTime(now.toEpochMilli());
                state.setLoadChangeTime(now.toEpochMilli());
                state.setLastFaultCheckTime(now.toEpochMilli());
                state.setInitialized(true);
            }

            updateState(state, deltaMs, now.toEpochMilli());

            ShuttleDataPoint point = ShuttleDataPoint.builder()
                    .shuttleId(state.getShuttleId())
                    .level(state.getLevel())
                    .aisle(state.getAisle())
                    .x(Math.round(state.getX() * 100.0) / 100.0)
                    .y(state.getLevel() * 3.0)
                    .z(Math.round(state.getZ() * 100.0) / 100.0)
                    .batteryLevel(Math.round(state.getBatteryLevel() * 10.0) / 10.0)
                    .hasLoad(state.isHasLoad())
                    .speed(Math.round(state.getSpeed() * 100.0) / 100.0)
                    .status(state.getStatus())
                    .currentLoad(Math.round(state.getCurrentLoad() * 100.0) / 100.0)
                    .hasFault(state.isHasFault())
                    .faultType(state.getFaultType())
                    .faultSeverity(Math.round(state.getFaultSeverity() * 100.0) / 100.0)
                    .collisionValue(Math.round(state.getCollisionValue() * 100.0) / 100.0)
                    .timestamp(now)
                    .build();

            points.add(point);
        }

        return points;
    }

    public List<ShuttleStatus> getAllCurrentStatus() {
        List<ShuttleStatus> statuses = new ArrayList<>();
        Instant now = Instant.now();

        for (ShuttleState state : shuttleStates.values()) {
            ShuttleStatus status = ShuttleStatus.builder()
                    .shuttleId(state.getShuttleId())
                    .level(state.getLevel())
                    .aisle(state.getAisle())
                    .x(Math.round(state.getX() * 100.0) / 100.0)
                    .y(state.getLevel() * 3.0)
                    .z(Math.round(state.getZ() * 100.0) / 100.0)
                    .batteryLevel(Math.round(state.getBatteryLevel() * 10.0) / 10.0)
                    .hasLoad(state.isHasLoad())
                    .speed(Math.round(state.getSpeed() * 100.0) / 100.0)
                    .status(state.getStatus())
                    .timestamp(now)
                    .currentLoad(Math.round(state.getCurrentLoad() * 100.0) / 100.0)
                    .hasFault(state.isHasFault())
                    .faultType(state.getFaultType())
                    .faultSeverity(Math.round(state.getFaultSeverity() * 100.0) / 100.0)
                    .collisionValue(Math.round(state.getCollisionValue() * 100.0) / 100.0)
                    .build();
            statuses.add(status);
        }

        return statuses;
    }

    public int getLevels() {
        return levels;
    }

    public int getAisles() {
        return aisles;
    }

    public double getTrackLength() {
        return trackLength;
    }

    public double getTrackWidth() {
        return trackWidth;
    }

    private void updateState(ShuttleState state, long deltaMs, long now) {
        double deltaSeconds = deltaMs / 1000.0;

        if (state.isHasFault()) {
            updateFaultState(state, deltaSeconds, now);
            updateCurrentLoad(state, deltaSeconds);
            return;
        }

        if (now - state.getLastFaultCheckTime() > 1000) {
            checkForFault(state, now);
            state.setLastFaultCheckTime(now);
        }

        if (state.getBatteryLevel() < 10) {
            state.setStatus("CHARGING");
            state.setSpeed(0);
            state.setBatteryLevel(Math.min(100, state.getBatteryLevel() + deltaSeconds * 5));
            updateCurrentLoad(state, deltaSeconds);
            if (state.getBatteryLevel() >= 95) {
                state.setStatus("MOVING");
                state.setSpeed(state.getMaxSpeed());
                state.setStateChangeTime(now);
            }
            return;
        }

        if ("PICKING".equals(state.getStatus()) || "DROPPING".equals(state.getStatus())) {
            if (now - state.getStateChangeTime() > 3000) {
                state.setStatus("MOVING");
                state.setSpeed(state.getMaxSpeed());
                state.setStateChangeTime(now);
                if ("PICKING".equals(state.getStatus())) {
                    state.setHasLoad(true);
                } else {
                    state.setHasLoad(false);
                }
                setNewTarget(state);
            }
            updateCurrentLoad(state, deltaSeconds);
            return;
        }

        double moveDistance = state.getSpeed() * deltaSeconds;
        double dx = state.getTargetX() - state.getX();
        double dz = state.getTargetZ() - state.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);

        if (distance < 0.2) {
            if (random.nextDouble() < 0.15) {
                state.setStatus(random.nextBoolean() ? "PICKING" : "DROPPING");
                state.setSpeed(0);
                state.setStateChangeTime(now);
            } else {
                setNewTarget(state);
            }
        } else {
            double ratio = moveDistance / distance;
            state.setX(state.getX() + dx * ratio);
            state.setZ(state.getZ() + dz * ratio);
        }

        state.setBatteryLevel(Math.max(0, state.getBatteryLevel() - deltaSeconds * 0.05));

        if (now - state.getLoadChangeTime() > 15000 + (long)(random.nextDouble() * 20000)) {
            if (random.nextDouble() < 0.3) {
                state.setStatus(state.isHasLoad() ? "DROPPING" : "PICKING");
                state.setSpeed(0);
                state.setStateChangeTime(now);
                state.setLoadChangeTime(now);
            }
        }

        updateCurrentLoad(state, deltaSeconds);
    }

    private void checkForFault(ShuttleState state, long now) {
        if (random.nextDouble() < FAULT_CHANCE_PER_SECOND) {
            boolean isStuck = random.nextDouble() < 0.5;
            state.setHasFault(true);
            state.setFaultStartTime(now);
            state.setSpeed(0);

            if (isStuck) {
                state.setFaultType("STUCK");
                state.setFaultSeverity(0.5 + random.nextDouble() * 0.5);
                state.setCollisionValue(0);
                state.setStatus("FAULT_STUCK");
            } else {
                state.setFaultType("COLLISION");
                state.setCollisionValue(COLLISION_THRESHOLD + random.nextDouble() * 0.3);
                state.setFaultSeverity(Math.min(1.0, state.getCollisionValue()));
                state.setStatus("FAULT_COLLISION");
            }
        }
    }

    private void updateFaultState(ShuttleState state, double deltaSeconds, long now) {
        long faultDuration = now - state.getFaultStartTime();

        if (state.getCollisionValue() > 0) {
            state.setCollisionValue(state.getCollisionValue() + deltaSeconds * 0.01 * (random.nextDouble() * 0.5 + 0.75));
        }

        if (faultDuration > FAULT_RECOVERY_TIME_MS) {
            state.setHasFault(false);
            state.setFaultType(null);
            state.setFaultSeverity(0);
            state.setCollisionValue(0);
            state.setStatus("MOVING");
            state.setSpeed(state.getMaxSpeed());
            state.setStateChangeTime(now);
            state.setLastFaultCheckTime(now);
            setNewTarget(state);
        }

        updateCurrentLoad(state, deltaSeconds);
    }

    private void updateCurrentLoad(ShuttleState state, double deltaSeconds) {
        double base = state.getBaseCurrent() * state.getFatigueDegradation();
        double speedFactor = 1.0 + (state.getSpeed() / state.getMaxSpeed()) * 0.5;
        double loadFactor = state.isHasLoad() ? 1.3 : 1.0;
        double noise = (random.nextDouble() - 0.5) * 0.4;
        double target = base * speedFactor * loadFactor + noise;

        if (state.isHasFault()) {
            if ("STUCK".equals(state.getFaultType())) {
                target = base * 2.5 + random.nextDouble() * 3.0;
            } else if ("COLLISION".equals(state.getFaultType())) {
                target = base * 3.0 + random.nextDouble() * 4.0;
            }
        }

        double smoothing = Math.min(1.0, deltaSeconds * 5);
        state.setCurrentLoad(state.getCurrentLoad() + (target - state.getCurrentLoad()) * smoothing);
        state.setCurrentLoad(Math.max(0, state.getCurrentLoad()));
    }

    private void setNewTarget(ShuttleState state) {
        double margin = 1.0;
        state.setTargetX(margin + random.nextDouble() * (trackLength - 2 * margin));
        state.setTargetZ(margin + random.nextDouble() * (trackWidth - 2 * margin));
    }

    public Set<String> getAllShuttleIds() {
        return shuttleStates.keySet();
    }
}
