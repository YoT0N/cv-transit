package edu.ilkiv.transit.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP over SockJS WebSocket конфігурація.
 *
 * Клієнт підключається до /ws, підписується на /topic/vehicles
 * і отримує push-оновлення позицій транспорту.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Вбудований брокер для топіків (можна замінити на RabbitMQ у продакшені)
        registry.enableSimpleBroker("/topic");
        // Префікс для повідомлень від клієнта до сервера (якщо потрібно)
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*") // у продакшені — конкретний домен
                .withSockJS();
    }
}