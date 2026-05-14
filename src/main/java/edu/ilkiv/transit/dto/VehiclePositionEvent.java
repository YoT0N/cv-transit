package edu.ilkiv.transit.dto;

import edu.ilkiv.transit.model.TransportType;
import lombok.Builder;
import lombok.Data;

/**
 * Payload який летить клієнту через WebSocket.
 * Мінімальний набір полів — без source/externalId (внутрішні деталі).
 */
@Data
@Builder
public class VehiclePositionEvent {

    private Long vehicleId;       // наш канонічний id
    private String routeName;     // "10", "19A"
    private TransportType type;

    private Double lat;
    private Double lng;
    private Float speed;
    private Float bearing;

    private String busNumber;
    private String color;
    private Boolean online;
}