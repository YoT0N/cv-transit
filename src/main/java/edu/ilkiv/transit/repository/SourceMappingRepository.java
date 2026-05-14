package edu.ilkiv.transit.repository;

import edu.ilkiv.transit.model.DataSource;
import edu.ilkiv.transit.model.SourceMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SourceMappingRepository extends JpaRepository<SourceMapping, Long> {

    Optional<SourceMapping> findByEntityTypeAndSourceAndSourceId(
            String entityType,
            DataSource source,
            String sourceId
    );
}