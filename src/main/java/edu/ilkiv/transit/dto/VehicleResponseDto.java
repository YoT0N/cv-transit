package edu.ilkiv.transit.dto;

import edu.ilkiv.transit.model.TransportType;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * REST відповідь для одного транспортного засобу.
 */
@Data
@Builder
public class VehicleResponseDto {

    private Long id;
    private String routeName;
    private String routeColor;
    private TransportType type;

    private Double lat;
    private Double lng;
    private Float speed;
    private Float bearing;

    private String busNumber;
    private Boolean online;
    private OffsetDateTime lastSeen;
}