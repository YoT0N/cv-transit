package edu.ilkiv.transit.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;

import java.time.OffsetDateTime;

@Entity
@Table(
        name = "vehicles",
        uniqueConstraints = @UniqueConstraint(columnNames = {"external_id", "source"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "external_id", nullable = false, length = 64)
    private String externalId;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)   // ← каже Hibernate використати PostgreSQL enum cast
    @Column(nullable = false)
    private DataSource source;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_id")
    private Route route;

    @Column(name = "bus_number", length = 16)
    private String busNumber;

    @Column(nullable = false)
    private Double lat;

    @Column(nullable = false)
    private Double lng;

    private Float speed;

    private Float bearing;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isOnline = true;

    @Column(nullable = false)
    @Builder.Default
    private OffsetDateTime lastSeen = OffsetDateTime.now();
}