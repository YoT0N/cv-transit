package edu.ilkiv.transit.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;

import java.time.OffsetDateTime;

@Entity
@Table(name = "gps_history")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GpsHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @Column(nullable = false)
    private Double lat;

    @Column(nullable = false)
    private Double lng;

    private Float speed;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)   // ← PostgreSQL enum cast
    @Column(nullable = false)
    private DataSource source;

    @Column(nullable = false)
    @Builder.Default
    private OffsetDateTime recordedAt = OffsetDateTime.now();
}