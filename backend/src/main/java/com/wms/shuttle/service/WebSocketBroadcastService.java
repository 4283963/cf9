package com.wms.shuttle.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wms.shuttle.model.ShuttleStatus;
import com.wms.shuttle.websocket.ShuttleWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WebSocketBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(WebSocketBroadcastService.class);
    private final ShuttleWebSocketHandler webSocketHandler;
    private final ObjectMapper objectMapper;

    public WebSocketBroadcastService(ShuttleWebSocketHandler webSocketHandler, ObjectMapper objectMapper) {
        this.webSocketHandler = webSocketHandler;
        this.objectMapper = objectMapper;
    }

    public void broadcastStatus(List<ShuttleStatus> statuses) {
        try {
            String json = objectMapper.writeValueAsString(statuses);
            webSocketHandler.broadcast(json);
        } catch (Exception e) {
            log.error("Failed to broadcast shuttle status", e);
        }
    }
}
