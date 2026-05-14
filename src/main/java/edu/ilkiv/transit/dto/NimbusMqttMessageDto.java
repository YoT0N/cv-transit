package edu.ilkiv.transit.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * DTO для MQTT повідомлення від Nimbus через mqtt.flespi.io
 *
 * Topic:   nimbus/locator/{token}/{vehicleId}
 * Payload: {"msg":{"pos":{"x":25.94,"y":48.27,"s":14,"c":160},"t":...,"r":19055},"id":93760,"tm":...}
 *
 * Поля pos:
 *   x  → longitude
 *   y  → latitude
 *   s  → speed (км/год)
 *   c  → course/bearing (градуси 0–360)
 *
 * Поля msg:
 *   r  → route id у системі Nimbus
 *   t  → timestamp GPS фікси (Unix seconds)
 *   o  → timestamp останнього оновлення
 *   i  → інтервал (секунди між оновленнями)
 *   tt → uptime пристрою
 *
 * Верхній рівень:
 *   id → vehicle id (дублює останній сегмент топіку)
 *   tm → timestamp отримання сервером (Unix seconds)
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NimbusMqttMessageDto {

    private Long id;   // vehicle id
    private Long tm;   // server timestamp
    private Msg msg;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Msg {
        private Pos pos;
        private Long t;   // GPS timestamp
        private Long r;   // route id
        private Double i; // update interval sec
        private Long o;   // last update timestamp
        private Long tt;  // uptime
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Pos {
        private Double x; // longitude
        private Double y; // latitude
        private Integer s; // speed km/h
        private Integer c; // course/bearing degrees
    }
}