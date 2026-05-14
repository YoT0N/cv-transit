package edu.ilkiv.transit.dto;

import edu.ilkiv.transit.model.TransportType;
import lombok.Builder;
import lombok.Data;

/**
 * REST відповідь для маршруту.
 */
@Data
@Builder
public class RouteResponseDto {

    private Long id;
    private String name;
    private TransportType type;
    private String color;
    private Boolean isActive;
    private Integer vehicleCount; // скільки транспорту зараз онлайн
}