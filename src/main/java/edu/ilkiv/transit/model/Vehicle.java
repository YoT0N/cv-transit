package edu.ilkiv.transit.model;

import jakarta.persistence.*;
import lombok.*;

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

    /** ID транспорту у зовнішньому джерелі (imei, vehicleId тощо) */
    @Column(name = "external_id", nullable = false, length = 64)
    private String externalId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DataSource source;

    /** Маршрут, яким зараз їде транспорт */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_id")
    private Route route;

    /** Бортовий номер ("4811", "3459") */
    @Column(name = "bus_number", length = 16)
    private String busNumber;

    @Column(nullable = false)
    private Double lat;

    @Column(nullable = false)
    private Double lng;

    private Float speed;

    /** Напрямок руху в градусах (0–360) */
    private Float bearing;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isOnline = true;

    @Column(nullable = false)
    @Builder.Default
    private OffsetDateTime lastSeen = OffsetDateTime.now();
}