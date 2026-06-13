package com.wms.shuttle.repository;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.QueryApi;
import com.influxdb.client.WriteApi;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import com.wms.shuttle.model.ShuttleDataPoint;
import com.wms.shuttle.model.ShuttleStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Repository
public class ShuttleRepository {

    private final WriteApi writeApi;
    private final WriteApiBlocking writeApiBlocking;
    private final InfluxDBClient influxDBClient;

    @Value("${influxdb.bucket}")
    private String bucket;

    @Value("${influxdb.org}")
    private String org;

    public ShuttleRepository(WriteApi writeApi, WriteApiBlocking writeApiBlocking, InfluxDBClient influxDBClient) {
        this.writeApi = writeApi;
        this.writeApiBlocking = writeApiBlocking;
        this.influxDBClient = influxDBClient;
    }

    public void write(ShuttleDataPoint point) {
        writeApi.writeMeasurement(WritePrecision.MS, point);
    }

    public void writeBlocking(ShuttleDataPoint point) {
        writeApiBlocking.writeMeasurement(WritePrecision.MS, point);
    }

    public void writeBatch(List<ShuttleDataPoint> points) {
        writeApi.writeMeasurements(WritePrecision.MS, points);
    }

    public List<ShuttleStatus> queryTrajectory(String shuttleId, Instant startTime, Instant endTime) {
        String flux = String.format(
            "from(bucket: \"%s\") " +
            "|> range(start: %d, stop: %d) " +
            "|> filter(fn: (r) => r[\"_measurement\"] == \"shuttle_telemetry\") " +
            "|> filter(fn: (r) => r[\"shuttleId\"] == \"%s\") " +
            "|> pivot(rowKey: [\"_time\"], columnKey: [\"_field\"], valueColumn: \"_value\") " +
            "|> keep(columns: [\"_time\", \"shuttleId\", \"level\", \"aisle\", \"x\", \"y\", \"z\", \"batteryLevel\", \"hasLoad\", \"speed\", \"status\"]) " +
            "|> sort(columns: [\"_time\"]) ",
            bucket,
            startTime.toEpochMilli() * 1000000,
            endTime.toEpochMilli() * 1000000,
            shuttleId
        );

        QueryApi queryApi = influxDBClient.getQueryApi();
        List<FluxTable> tables = queryApi.query(flux, org);
        return convertToStatusList(tables);
    }

    public List<ShuttleStatus> queryLatestAllShuttles() {
        String flux = String.format(
            "from(bucket: \"%s\") " +
            "|> range(start: -1m) " +
            "|> filter(fn: (r) => r[\"_measurement\"] == \"shuttle_telemetry\") " +
            "|> pivot(rowKey: [\"_time\"], columnKey: [\"_field\"], valueColumn: \"_value\") " +
            "|> keep(columns: [\"_time\", \"shuttleId\", \"level\", \"aisle\", \"x\", \"y\", \"z\", \"batteryLevel\", \"hasLoad\", \"speed\", \"status\"]) " +
            "|> group(columns: [\"shuttleId\"]) " +
            "|> last(column: \"_time\") ",
            bucket
        );

        QueryApi queryApi = influxDBClient.getQueryApi();
        List<FluxTable> tables = queryApi.query(flux, org);
        return convertToStatusList(tables);
    }

    public List<String> getAllShuttleIds() {
        String flux = String.format(
            "import \"influxdata/influxdb/schema\" " +
            "schema.tagValues(bucket: \"%s\", tag: \"shuttleId\", start: -1h) ",
            bucket
        );

        QueryApi queryApi = influxDBClient.getQueryApi();
        List<FluxTable> tables = queryApi.query(flux, org);
        List<String> ids = new ArrayList<>();

        for (FluxTable table : tables) {
            for (FluxRecord record : table.getRecords()) {
                Object value = record.getValueByKey("_value");
                if (value != null) {
                    ids.add(value.toString());
                }
            }
        }
        return ids;
    }

    private List<ShuttleStatus> convertToStatusList(List<FluxTable> tables) {
        List<ShuttleStatus> statusList = new ArrayList<>();

        for (FluxTable table : tables) {
            for (FluxRecord record : table.getRecords()) {
                ShuttleStatus status = convertRecordToStatus(record);
                if (status != null) {
                    statusList.add(status);
                }
            }
        }
        return statusList;
    }

    private ShuttleStatus convertRecordToStatus(FluxRecord record) {
        try {
            return ShuttleStatus.builder()
                    .shuttleId(getString(record, "shuttleId"))
                    .level(getInteger(record, "level"))
                    .aisle(getInteger(record, "aisle"))
                    .x(getDouble(record, "x"))
                    .y(getDouble(record, "y"))
                    .z(getDouble(record, "z"))
                    .batteryLevel(getDouble(record, "batteryLevel"))
                    .hasLoad(getBoolean(record, "hasLoad"))
                    .speed(getDouble(record, "speed"))
                    .status(getString(record, "status"))
                    .timestamp(record.getTime())
                    .build();
        } catch (Exception e) {
            return null;
        }
    }

    private String getString(FluxRecord record, String key) {
        Object value = record.getValueByKey(key);
        return value != null ? value.toString() : null;
    }

    private Integer getInteger(FluxRecord record, String key) {
        Object value = record.getValueByKey(key);
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double getDouble(FluxRecord record, String key) {
        Object value = record.getValueByKey(key);
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean getBoolean(FluxRecord record, String key) {
        Object value = record.getValueByKey(key);
        if (value == null) return null;
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(value.toString());
    }
}
