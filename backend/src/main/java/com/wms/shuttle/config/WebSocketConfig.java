package com.wms.shuttle.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final com.wms.shuttle.websocket.ShuttleWebSocketHandler shuttleWebSocketHandler;

    public WebSocketConfig(com.wms.shuttle.websocket.ShuttleWebSocketHandler shuttleWebSocketHandler) {
        this.shuttleWebSocketHandler = shuttleWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(shuttleWebSocketHandler, "/ws/shuttles")
                .setAllowedOriginPatterns("*");
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(819200);
        container.setMaxBinaryMessageBufferSize(819200);
        container.setMaxSessionIdleTimeout(300000L);
        return container;
    }
}
