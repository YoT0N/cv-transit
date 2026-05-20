package edu.ilkiv.transit.dto.admin;

import lombok.Builder;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Builder
public class AdminRouteResponseDto {
    private Long    id;
    private String  name;
    private String  type;
    private String  color;
    private Boolean isActive;
    private Integer vehicleCount;
    private OffsetDateTime updatedAt;
}