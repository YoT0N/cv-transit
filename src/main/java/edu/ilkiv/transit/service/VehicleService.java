package edu.ilkiv.transit.service;

import edu.ilkiv.transit.dto.VehicleResponseDto;
import edu.ilkiv.transit.model.Vehicle;
import edu.ilkiv.transit.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static edu.ilkiv.transit.config.CacheConfig.CACHE_VEHICLES;

@Service
@RequiredArgsConstructor
public class VehicleService {

    private final VehicleRepository vehicleRepository;

    @Cacheable(CACHE_VEHICLES)
    @Transactional(readOnly = true)
    public List<VehicleResponseDto> getAllOnline() {
        return vehicleRepository.findByIsOnlineTrue().stream()
                .map(this::toDto)
                .toList();
    }

    @Cacheable(value = CACHE_VEHICLES, key = "'route:' + #routeId")
    @Transactional(readOnly = true)
    public List<VehicleResponseDto> getByRoute(Long routeId) {
        return vehicleRepository.findByRouteIdAndIsOnlineTrue(routeId).stream()
                .map(this::toDto)
                .toList();
    }

    // nearby не кешуємо — унікальні координати щоразу різні
    @Transactional(readOnly = true)
    public List<VehicleResponseDto> getNearby(double lat, double lng, double radiusKm) {
        return vehicleRepository.findNearby(lat, lng, radiusKm).stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Викликається з VehicleAggregationService після кожного polling —
     * інвалідує кеш щоб наступний REST запит отримав свіжі дані.
     */
    @CacheEvict(value = CACHE_VEHICLES, allEntries = true)
    public void evictCache() {
        // тільки інвалідація
    }

    private VehicleResponseDto toDto(Vehicle v) {
        return VehicleResponseDto.builder()
                .id(v.getId())
                .routeName(v.getRoute() != null ? v.getRoute().getName() : null)
                .routeColor(v.getRoute() != null ? v.getRoute().getColor() : null)
                .type(v.getRoute() != null ? v.getRoute().getType() : null)
                .lat(v.getLat())
                .lng(v.getLng())
                .speed(v.getSpeed())
                .bearing(v.getBearing())
                .busNumber(v.getBusNumber())
                .online(v.getIsOnline())
                .lastSeen(v.getLastSeen())
                .build();
    }
}