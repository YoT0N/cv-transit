package edu.ilkiv.transit.repository;

import edu.ilkiv.transit.model.GpsHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface GpsHistoryRepository extends JpaRepository<GpsHistory, Long> {

    /** Трек конкретного транспорту за часовий проміжок */
    List<GpsHistory> findByVehicleIdAndRecordedAtAfterOrderByRecordedAtAsc(
            Long vehicleId,
            OffsetDateTime after
    );
}