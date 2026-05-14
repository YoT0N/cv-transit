package edu.ilkiv.transit.repository;

import edu.ilkiv.transit.model.Stop;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StopRepository extends JpaRepository<Stop, Long> {

    /**
     * Зупинки в радіусі radiusKm км від точки (lat, lng).
     * Формула Haversine — без PostGIS, працює на чистому PostgreSQL.
     */
    @Query("""
        SELECT s FROM Stop s
        WHERE (6371 * acos(
            cos(radians(:lat)) * cos(radians(s.lat)) *
            cos(radians(s.lng) - radians(:lng)) +
            sin(radians(:lat)) * sin(radians(s.lat))
        )) < :radiusKm
        """)
    List<Stop> findNearby(
            @Param("lat") double lat,
            @Param("lng") double lng,
            @Param("radiusKm") double radiusKm
    );
}