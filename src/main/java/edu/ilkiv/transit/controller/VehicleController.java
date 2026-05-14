package edu.ilkiv.transit.controller;

import edu.ilkiv.transit.dto.VehicleResponseDto;
import edu.ilkiv.transit.service.VehicleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API для транспортних засобів.
 *
 * GET /api/vehicles          — всі онлайн
 * GET /api/vehicles/nearby   — в радіусі від точки
 * GET /api/vehicles/route/{routeId} — по маршруту
 */
@RestController
@RequestMapping("/api/vehicles")
@RequiredArgsConstructor
public class VehicleController {

    private final VehicleService vehicleService;

    @GetMapping
    public ResponseEntity<List<VehicleResponseDto>> getAllOnline() {
        return ResponseEntity.ok(vehicleService.getAllOnline());
    }

    @GetMapping("/route/{routeId}")
    public ResponseEntity<List<VehicleResponseDto>> getByRoute(
            @PathVariable Long routeId) {
        return ResponseEntity.ok(vehicleService.getByRoute(routeId));
    }

    @GetMapping("/nearby")
    public ResponseEntity<List<VehicleResponseDto>> getNearby(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(defaultValue = "0.5") double radiusKm) {
        return ResponseEntity.ok(vehicleService.getNearby(lat, lng, radiusKm));
    }
}