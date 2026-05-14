package edu.ilkiv.transit.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(
        name = "route_stops",
        uniqueConstraints = @UniqueConstraint(columnNames = {"route_id", "stop_id", "stop_order"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RouteStop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_id", nullable = false)
    private Route route;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stop_id", nullable = false)
    private Stop stop;

    /** Порядковий номер зупинки в маршруті */
    @Column(nullable = false)
    private Integer stopOrder;

    /** Секунди від початку доби (з розкладу Nimbus), може бути null */
    private Integer arrivalSec;
}