package edu.ilkiv.transit.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "source_mappings",
        uniqueConstraints = @UniqueConstraint(columnNames = {"entity_type", "source", "source_id"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Тип сутності: "route", "stop", "vehicle"
     * Визначає в якій таблиці шукати canonicalId
     */
    @Column(name = "entity_type", nullable = false, length = 16)
    private String entityType;

    /** ID у канонічній таблиці (routes.id, stops.id тощо) */
    @Column(name = "canonical_id", nullable = false)
    private Long canonicalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DataSource source;

    /** ID як він виглядає у зовнішньому джерелі */
    @Column(name = "source_id", nullable = false, length = 64)
    private String sourceId;
}