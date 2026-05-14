package edu.ilkiv.transit.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.ilkiv.transit.dto.NimbusMqttMessageDto;
import edu.ilkiv.transit.dto.VehiclePositionDto;
import edu.ilkiv.transit.model.DataSource;
import edu.ilkiv.transit.model.TransportType;
import edu.ilkiv.transit.service.VehicleAggregationService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;
import java.util.UUID;

/**
 * Підписується на MQTT broker mqtt.flespi.io і отримує real-time позиції
 * транспорту з системи Nimbus (nimbus.cv.km-trade.net).
 *
 * Протокол: MQTT over WebSocket (wss://)
 * Topic:    nimbus/locator/{token}/# — wildcard, всі vehicle одразу
 *
 * Кожне повідомлення = оновлення позиції одного транспортного засобу.
 * На відміну від polling-колекторів (TransGps, TransportCv) — push-based,
 * тому затримка мінімальна (< 1 сек).
 *
 * Автентифікація: анонімна (токен вбудований у topic, пароль не потрібен).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "nimbus.mqtt.enabled", havingValue = "true", matchIfMissing = false)
public class MqttCollector implements MqttCallback {

    // URI WebSocket брокера (wss:// = TLS, ws:// = plain)
    @Value("${nimbus.mqtt.broker-uri:wss://mqtt.flespi.io/mqtt}")
    private String brokerUri;

    // Токен Nimbus — частина топіку, не пароль до брокера
    @Value("${nimbus.mqtt.token:756022170f424d008f931dfb6912dff7}")
    private String nimbusToken;

    // QoS 0 = at-most-once, підходить для GPS (краще пропустити ніж дублювати)
    @Value("${nimbus.mqtt.qos:0}")
    private int qos;

    private final VehicleAggregationService aggregationService;
    private final ObjectMapper objectMapper;

    private MqttClient mqttClient;

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @PostConstruct
    public void connect() {
        try {
            String clientId = "cv-transit-" + UUID.randomUUID().toString().substring(0, 8);

            // MemoryPersistence — не зберігає стан між рестартами (підходить для QoS 0)
            mqttClient = new MqttClient(brokerUri, clientId, new MemoryPersistence());
            mqttClient.setCallback(this);

            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setAutomaticReconnect(true);
            options.setConnectionTimeout(15);
            options.setKeepAliveInterval(30);
            // Flespi потребує токен як username, пароль — порожній рядок
            options.setUserName(nimbusToken);
            options.setPassword("".toCharArray());

            log.info("MqttCollector: connecting to {}...", brokerUri);
            mqttClient.connect(options);
            log.info("MqttCollector: connected successfully");


            String topic = "nimbus/locator/" + nimbusToken + "/#";
            mqttClient.subscribe(topic, qos);
            log.info("MqttCollector: subscribed to '{}'", topic);

        } catch (MqttException e) {
            log.error("MqttCollector: failed to connect — {} (reason code: {})",
                    e.getMessage(), e.getReasonCode());
        }
    }

    @PreDestroy
    public void disconnect() {
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                mqttClient.disconnect();
                log.info("MqttCollector: disconnected");
            } catch (MqttException e) {
                log.warn("MqttCollector: error during disconnect — {}", e.getMessage());
            }
        }
    }

    // ── MqttCallback ─────────────────────────────────────────────────────────

    /**
     * Викликається при кожному новому повідомленні від брокера.
     * topic формат: nimbus/locator/{token}/{vehicleId}
     */
    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            String payload = new String(message.getPayload());
            NimbusMqttMessageDto dto = objectMapper.readValue(payload, NimbusMqttMessageDto.class);

            VehiclePositionDto position = toPositionDto(dto, topic);
            if (position == null) return;

            aggregationService.processPositions(List.of(position));

        } catch (Exception e) {
            log.warn("MqttCollector: failed to parse message on topic '{}': {}", topic, e.getMessage());
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MqttCollector: connection lost — {}", cause.getMessage());
        // automaticReconnect=true — Paho перепідключиться автоматично
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // Ми тільки subscribe, не publish — цей callback не використовується
    }

    // ── Mapping ──────────────────────────────────────────────────────────────

    private VehiclePositionDto toPositionDto(NimbusMqttMessageDto dto, String topic) {
        if (dto.getId() == null || dto.getMsg() == null || dto.getMsg().getPos() == null) {
            log.debug("MqttCollector: skipping incomplete message on topic '{}'", topic);
            return null;
        }

        NimbusMqttMessageDto.Pos pos = dto.getMsg().getPos();

        // Валідація координат (Чернівецька область: приблизно 47.8–48.6 lat, 25.5–26.3 lng)
        if (pos.getY() == null || pos.getX() == null) return null;
        if (pos.getY() < 47.0 || pos.getY() > 50.0) return null;
        if (pos.getX() < 24.0 || pos.getX() > 28.0) return null;

        return VehiclePositionDto.builder()
                .externalId(String.valueOf(dto.getId()))
                .source(DataSource.nimbus)
                .externalRouteId(dto.getMsg().getR() != null
                        ? String.valueOf(dto.getMsg().getR())
                        : null)
                .type(TransportType.BUS) // Nimbus не передає тип — за замовчуванням BUS
                .lat(pos.getY())         // y = latitude
                .lng(pos.getX())         // x = longitude
                .speed(pos.getS() != null ? pos.getS().floatValue() : 0f)
                .bearing(pos.getC() != null ? pos.getC().floatValue() : 0f)
                .online(true)            // якщо прийшло повідомлення — онлайн
                .build();
    }
}