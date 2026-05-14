package edu.ilkiv.transit.service;

import edu.ilkiv.transit.dto.RouteResponseDto;
import edu.ilkiv.transit.model.Route;
import edu.ilkiv.transit.repository.RouteRepository;
import edu.ilkiv.transit.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static edu.ilkiv.transit.config.CacheConfig.CACHE_ROUTES;

@Service
@RequiredArgsConstructor
public class RouteService {

    private final RouteRepository routeRepository;
    private final VehicleRepository vehicleRepository;

    /**
     * Маршрути змінюються рідко — кешуємо на 60 сек.
     */
    @Cacheable(CACHE_ROUTES)
    @Transactional(readOnly = true)
    public List<RouteResponseDto> getAllActive() {
        return routeRepository.findByIsActiveTrue().stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Викликати після будь-яких змін у маршрутах
     * (наприклад, якщо додаємо адмін-ендпоінт у майбутньому).
     */
    @CacheEvict(value = CACHE_ROUTES, allEntries = true)
    public void evictCache() {
        // тільки інвалідація
    }

    private RouteResponseDto toDto(Route route) {
        int count = vehicleRepository.findByRouteIdAndIsOnlineTrue(route.getId()).size();
        return RouteResponseDto.builder()
                .id(route.getId())
                .name(route.getName())
                .type(route.getType())
                .color(route.getColor())
                .isActive(route.getIsActive())
                .vehicleCount(count)
                .build();
    }
}