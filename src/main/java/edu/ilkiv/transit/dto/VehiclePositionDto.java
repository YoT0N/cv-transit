package edu.ilkiv.transit.dto;

import edu.ilkiv.transit.model.DataSource;
import edu.ilkiv.transit.model.TransportType;
import lombok.Builder;
import lombok.Data;

/**
 * Єдина внутрішня модель позиції транспорту після агрегації з будь-якого джерела.
 * Саме цей об'єкт зберігається в БД і передається на фронтенд через WebSocket.
 */
@Data
@Builder
public class VehiclePositionDto {

    private String externalId;      // id у джерелі
    private DataSource source;      // звідки дані

    private String routeName;       // "10", "19A"
    private String externalRouteId; // id маршруту у джерелі
    private TransportType type;     // BUS / TROLL / TRAM

    private Double lat;
    private Double lng;
    private Float speed;
    private Float bearing;

    private String busNumber;       // бортовий номер
    private String color;           // колір маршруту
    private Boolean online;
}