package edu.ilkiv.transit.dto.admin;

import lombok.Builder;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Builder
public class GpsHistoryResponseDto {
    private Long   id;
    private Long   vehicleId;
    private Double lat;
    private Double lng;
    private Float  speed;
    private String source;
    private OffsetDateTime recordedAt;
}