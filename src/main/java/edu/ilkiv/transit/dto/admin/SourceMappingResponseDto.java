package edu.ilkiv.transit.dto.admin;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SourceMappingResponseDto {
    private Long   id;
    private String entityType;
    private Long   canonicalId;
    private String source;
    private String sourceId;
}