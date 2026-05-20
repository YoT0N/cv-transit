package edu.ilkiv.transit.dto.admin;

import lombok.Builder;
import lombok.Data;
import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
public class CacheEvictResponseDto {
    private List<String>   evictedCaches;
    private OffsetDateTime evictedAt;
}