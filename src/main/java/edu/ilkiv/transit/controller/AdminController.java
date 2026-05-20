package edu.ilkiv.transit.controller;

import edu.ilkiv.transit.dto.admin.*;
import edu.ilkiv.transit.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API для адміністратора.
 *
 * Всі ендпоінти під /api/admin — у майбутньому можна
 * закрити Spring Security роллю ADMIN.
 *
 * GET  /api/admin/vehicles/history/{vehicleId}     — GPS-трек транспорту
 * GET  /api/admin/vehicles/offline                 — офлайн транспорт
 * GET  /api/admin/routes                           — всі маршрути (включно з неактивними)
 * POST /api/admin/routes                           — створити маршрут
 * PUT  /api/admin/routes/{id}                      — оновити маршрут
 * DELETE /api/admin/routes/{id}                    — деактивувати маршрут
 * GET  /api/admin/mappings                         — переглянути source_mappings
 * DELETE /api/admin/mappings/{id}                  — видалити mapping
 * POST /api/admin/cache/evict                      — інвалідувати весь кеш
 * GET  /api/admin/collectors/status                — статус колекторів
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminService adminService;

    // ── GPS-історія ────────────────────────────────────────────────────────

    /**
     * GPS-трек конкретного транспортного засобу за останні N годин.
     * @param vehicleId  канонічний id у таблиці vehicles
     * @param hours      глибина вибірки (за замовчуванням 24 год)
     */
    @GetMapping("/vehicles/history/{vehicleId}")
    public ResponseEntity<List<GpsHistoryResponseDto>> getVehicleHistory(
            @PathVariable Long vehicleId,
            @RequestParam(defaultValue = "24") int hours) {
        return ResponseEntity.ok(adminService.getVehicleHistory(vehicleId, hours));
    }

    // ── Офлайн транспорт ───────────────────────────────────────────────────

    /**
     * Список транспортних засобів що зараз офлайн (is_online = false).
     * Корисно для діагностики дедублікації та втрати зв'язку.
     */
    @GetMapping("/vehicles/offline")
    public ResponseEntity<List<OfflineVehicleResponseDto>> getOfflineVehicles() {
        return ResponseEntity.ok(adminService.getOfflineVehicles());
    }

    // ── Маршрути ───────────────────────────────────────────────────────────

    /**
     * Всі маршрути — активні і неактивні (на відміну від /api/routes).
     */
    @GetMapping("/routes")
    public ResponseEntity<List<AdminRouteResponseDto>> getAllRoutes() {
        return ResponseEntity.ok(adminService.getAllRoutes());
    }

    /**
     * Створити новий маршрут вручну.
     * Тіло: { "name": "42", "type": "BUS", "color": "#FF0000" }
     */
    @PostMapping("/routes")
    public ResponseEntity<AdminRouteResponseDto> createRoute(
            @RequestBody AdminRouteRequestDto request) {
        return ResponseEntity.ok(adminService.createRoute(request));
    }

    /**
     * Оновити існуючий маршрут (назва, тип, колір, isActive).
     */
    @PutMapping("/routes/{id}")
    public ResponseEntity<AdminRouteResponseDto> updateRoute(
            @PathVariable Long id,
            @RequestBody AdminRouteRequestDto request) {
        return ResponseEntity.ok(adminService.updateRoute(id, request));
    }

    /**
     * М'яке видалення — встановлює is_active = false.
     * Транспорт на маршруті залишається у БД.
     */
    @DeleteMapping("/routes/{id}")
    public ResponseEntity<Void> deactivateRoute(@PathVariable Long id) {
        adminService.deactivateRoute(id);
        return ResponseEntity.noContent().build();
    }

    // ── Source Mappings ────────────────────────────────────────────────────

    /**
     * Всі записи таблиці source_mappings — зв'язки між зовнішніми id та канонічними.
     * Опційна фільтрація: ?source=transgps або ?entityType=route
     */
    @GetMapping("/mappings")
    public ResponseEntity<List<SourceMappingResponseDto>> getMappings(
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String entityType) {
        return ResponseEntity.ok(adminService.getMappings(source, entityType));
    }

    /**
     * Видалити конкретний source mapping.
     * Корисно якщо маппінг зв'язав неправильні маршрути між джерелами.
     */
    @DeleteMapping("/mappings/{id}")
    public ResponseEntity<Void> deleteMapping(@PathVariable Long id) {
        adminService.deleteMapping(id);
        return ResponseEntity.noContent().build();
    }

    // ── Кеш ───────────────────────────────────────────────────────────────

    /**
     * Примусово інвалідувати кеш маршрутів і транспорту (Redis).
     * Наступний запит до /api/routes і /api/vehicles піде прямо в БД.
     */
    @PostMapping("/cache/evict")
    public ResponseEntity<CacheEvictResponseDto> evictCache() {
        return ResponseEntity.ok(adminService.evictAllCaches());
    }

    // ── Статус колекторів ─────────────────────────────────────────────────

    /**
     * Показує які колектори активні (enabled через @ConditionalOnProperty),
     * коли останній раз отримували дані, скільки транспорту від кожного джерела.
     */
    @GetMapping("/collectors/status")
    public ResponseEntity<List<CollectorStatusDto>> getCollectorsStatus() {
        return ResponseEntity.ok(adminService.getCollectorsStatus());
    }
}