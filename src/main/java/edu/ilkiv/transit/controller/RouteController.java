package edu.ilkiv.transit.controller;

import edu.ilkiv.transit.dto.RouteResponseDto;
import edu.ilkiv.transit.service.RouteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API для маршрутів.
 *
 * GET /api/routes — всі активні маршрути з кількістю транспорту
 */
@RestController
@RequestMapping("/api/routes")
@RequiredArgsConstructor
public class RouteController {

    private final RouteService routeService;

    @GetMapping
    public ResponseEntity<List<RouteResponseDto>> getAllActive() {
        return ResponseEntity.ok(routeService.getAllActive());
    }
}