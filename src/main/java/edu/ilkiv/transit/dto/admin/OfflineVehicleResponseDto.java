package edu.ilkiv.transit.dto.admin;

import lombok.Builder;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Builder
public class OfflineVehicleResponseDto {
    private Long   id;
    private String externalId;
    private String source;
    private String routeName;
    private String busNumber;
    private Double lat;
    private Double lng;
    private OffsetDateTime lastSeen;
}