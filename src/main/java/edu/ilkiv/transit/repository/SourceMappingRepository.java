package edu.ilkiv.transit.repository;

import edu.ilkiv.transit.model.DataSource;
import edu.ilkiv.transit.model.SourceMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SourceMappingRepository extends JpaRepository<SourceMapping, Long> {

    /**
     * Явний CAST до data_source enum потрібен бо PostgreSQL не робить
     * implicit cast із varchar → custom enum type.
     */
    @Query(value = """
        SELECT * FROM source_mappings
        WHERE entity_type = :entityType
          AND source      = CAST(:source AS data_source)
          AND source_id   = :sourceId
        LIMIT 1
        """, nativeQuery = true)
    Optional<SourceMapping> findByEntityTypeAndSourceAndSourceId(
            @Param("entityType") String entityType,
            @Param("source")     String source,
            @Param("sourceId")   String sourceId
    );
}