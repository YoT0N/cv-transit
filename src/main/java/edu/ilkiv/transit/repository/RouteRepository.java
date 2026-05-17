package edu.ilkiv.transit.repository;

import edu.ilkiv.transit.model.Route;
import edu.ilkiv.transit.model.TransportType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RouteRepository extends JpaRepository<Route, Long> {

    Optional<Route> findByName(String name);

    List<Route> findByIsActiveTrue();

    List<Route> findByTypeAndIsActiveTrue(TransportType type);

    Optional<Route> findByNameAndType(String name, TransportType type);

}