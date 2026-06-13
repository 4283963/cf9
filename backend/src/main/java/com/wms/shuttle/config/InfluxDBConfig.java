package com.wms.shuttle.config;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApi;
import com.influxdb.client.WriteApiBlocking;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class InfluxDBConfig {

    @Value("${influxdb.url}")
    private String url;

    @Value("${influxdb.token}")
    private String token;

    @Value("${influxdb.org}")
    private String org;

    @Value("${influxdb.bucket}")
    private String bucket;

    @Value("${influxdb.connect-timeout}")
    private Duration connectTimeout;

    @Value("${influxdb.read-timeout}")
    private Duration readTimeout;

    @Value("${influxdb.write-timeout}")
    private Duration writeTimeout;

    @Bean
    public InfluxDBClient influxDBClient() {
        return InfluxDBClientFactory.create(url, token.toCharArray(), org, bucket)
                .setConnectTimeout(connectTimeout)
                .setReadTimeout(readTimeout)
                .setWriteTimeout(writeTimeout);
    }

    @Bean
    public WriteApi writeApi(InfluxDBClient client) {
        return client.makeWriteApi();
    }

    @Bean
    public WriteApiBlocking writeApiBlocking(InfluxDBClient client) {
        return client.getWriteApiBlocking();
    }

    @Bean
    public String influxBucket() {
        return bucket;
    }

    @Bean
    public String influxOrg() {
        return org;
    }
}
