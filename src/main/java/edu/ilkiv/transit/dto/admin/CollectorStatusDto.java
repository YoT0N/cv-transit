package edu.ilkiv.transit.dto.admin;

import lombok.Builder;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@Builder
public class CollectorStatusDto {
    private String source;
    private Integer onlineVehicles;
    private OffsetDateTime lastReceivedAt;
}