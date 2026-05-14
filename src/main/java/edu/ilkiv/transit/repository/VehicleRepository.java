package edu.ilkiv.transit.repository;

import edu.ilkiv.transit.model.DataSource;
import edu.ilkiv.transit.model.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    Optional<Vehicle> findByExternalIdAndSource(String externalId, DataSource source);

    List<Vehicle> findByIsOnlineTrue();

    List<Vehicle> findByRouteIdAndIsOnlineTrue(Long routeId);

    /**
     * Транспорт у радіусі radiusKm км від точки (lat, lng).
     */
    @Query("""
        SELECT v FROM Vehicle v
        WHERE v.isOnline = true
        AND (6371 * acos(
            cos(radians(:lat)) * cos(radians(v.lat)) *
            cos(radians(v.lng) - radians(:lng)) +
            sin(radians(:lat)) * sin(radians(v.lat))
        )) < :radiusKm
        """)
    List<Vehicle> findNearby(
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("radiusKm") double radiusKm
    );
}