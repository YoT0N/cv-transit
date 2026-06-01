package edu.ilkiv.transit.service;

import edu.ilkiv.transit.dto.VehiclePositionDto;
import edu.ilkiv.transit.dto.VehiclePositionEvent;
import edu.ilkiv.transit.dto.admin.*;
import edu.ilkiv.transit.model.*;
import edu.ilkiv.transit.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.junit.jupiter.MockitoSettings.*;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Тести для підвищення BRANCH coverage у service шарі.
 * Цільові сервіси:
 *   - VehicleAggregationService: 62% → 80%+
 *   - AdminService:              68% → 80%+
 *   - VehicleBroadcastService:   60% → 80%+
 *   - RouteService:              непокритий evictCache() метод
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Service layer — branch coverage tests")
class ServiceBranchCoverageTest {

    // ════════════════════════════════════════════════════════════════════════
    // VehicleAggregationService
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("VehicleAggregationService — branch coverage")
    class VehicleAggregationServiceBranchTests {

        @Mock VehicleRepository vehicleRepository;
        @Mock RouteRepository routeRepository;
        @Mock SourceMappingRepository sourceMappingRepository;
        @Mock GpsHistoryRepository gpsHistoryRepository;
        @Mock VehicleBroadcastService broadcastService;
        @Mock VehicleService vehicleService;

        VehicleAggregationService aggregationService;

        @BeforeEach
        void setUp() {
            aggregationService = new VehicleAggregationService(
                    vehicleRepository, routeRepository, sourceMappingRepository,
                    gpsHistoryRepository, broadcastService, vehicleService);

            when(vehicleRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(routeRepository.save(any())).thenAnswer(i -> {
                Route r = i.getArgument(0);
                if (r.getId() == null) r.setId(99L);
                return r;
            });
            when(gpsHistoryRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(sourceMappingRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        }

        // ── markAbsentVehiclesOffline — гілка nowOffline.isEmpty() ────────────

        /**
         * Гілка: всі vehicles від джерела є у батчі → nowOffline порожній → return одразу.
         * if (nowOffline.isEmpty()) return;  ← ця гілка раніше не покривалась
         */
        @Test
        @DisplayName("markAbsentVehiclesOffline — всі vehicles у батчі → ніхто не йде офлайн")
        void markAbsent_allVehiclesPresent_noneSetOffline() {
            Vehicle existing = buildVehicle(1L, "imei-001", DataSource.transgps, true);
            when(vehicleRepository.findBySourceAndIsOnlineTrue(DataSource.transgps))
                    .thenReturn(List.of(existing));
            when(vehicleRepository.findByExternalIdAndSource("imei-001", DataSource.transgps))
                    .thenReturn(Optional.of(existing));
            when(sourceMappingRepository.findByEntityTypeAndSourceAndSourceId(any(), any(), any()))
                    .thenReturn(Optional.empty());
            when(routeRepository.findByNameAndType(any(), any())).thenReturn(Optional.empty());
            when(vehicleRepository.findNearbyFromOtherSource(anyDouble(), anyDouble(),
                    anyDouble(), any(), any())).thenReturn(Collections.emptyList());

            // Батч містить той самий externalId → nowOffline буде порожнім
            aggregationService.processPositions(List.of(buildDto("imei-001", DataSource.transgps, "10", "r10")));

            // saveAll НЕ має викликатись (нема кого ставити офлайн)
            verify(vehicleRepository, never()).saveAll(anyList());
        }

        // ── deduplicateWithinBatch — merge lambda ─────────────────────────────

        /**
         * Гілка: два vehicles з однаковим busNumber → merge lambda виконується,
         * перший зберігається, другий відкидається.
         * seen.merge(key, dto, (existing, incoming) -> existing)  ← lambda
         */
        @Test
        @DisplayName("deduplicateWithinBatch — два vehicles з однаковим busNumber → дублікат відкидається")
        void deduplicateWithinBatch_duplicateBusNumber_firstKept() {
            VehiclePositionDto dto1 = buildDto("ext-1", DataSource.transgps, "10", "r10");
            VehiclePositionDto dto2 = buildDto("ext-2", DataSource.transgps, "10", "r10");
            // Обидва мають busNumber "1234" (дефолт у buildDto)

            when(vehicleRepository.findBySourceAndIsOnlineTrue(DataSource.transgps))
                    .thenReturn(Collections.emptyList());
            when(vehicleRepository.findByExternalIdAndSource(anyString(), eq(DataSource.transgps)))
                    .thenReturn(Optional.empty());
            when(sourceMappingRepository.findByEntityTypeAndSourceAndSourceId(any(), any(), any()))
                    .thenReturn(Optional.empty());
            when(routeRepository.findByNameAndType(any(), any())).thenReturn(Optional.empty());
            when(vehicleRepository.findNearbyFromOtherSource(anyDouble(), anyDouble(),
                    anyDouble(), any(), any())).thenReturn(Collections.emptyList());

            aggregationService.processPositions(List.of(dto1, dto2));

            // Після дедуплікації — save має бути тільки один раз для vehicle (не два)
            ArgumentCaptor<Vehicle> captor = ArgumentCaptor.forClass(Vehicle.class);
            verify(vehicleRepository, times(1)).save(argThat(v ->
                    v.getExternalId().equals("ext-1")));
            verify(vehicleRepository, never()).save(argThat(v ->
                    v.getExternalId().equals("ext-2")));
        }

        // ── buildDeduplicationKey — busNumber null і blank гілки ─────────────

        /**
         * Гілка: busNumber = null → ключ будується по externalId.
         * if (dto.getBusNumber() != null && !dto.getBusNumber().isBlank())
         *   → false (null) → "source:id:externalId"
         */
        @Test
        @DisplayName("buildDeduplicationKey — busNumber=null → ключ по externalId, не дедуплікується")
        void deduplicationKey_nullBusNumber_usesExternalId() {
            VehiclePositionDto dto1 = VehiclePositionDto.builder()
                    .externalId("ext-A").source(DataSource.transgps)
                    .routeName("5").externalRouteId("r5").type(TransportType.BUS)
                    .lat(48.29).lng(25.93).speed(0f).bearing(0f)
                    .busNumber(null)  // ← null
                    .online(true).build();
            VehiclePositionDto dto2 = VehiclePositionDto.builder()
                    .externalId("ext-B").source(DataSource.transgps)
                    .routeName("5").externalRouteId("r5").type(TransportType.BUS)
                    .lat(48.30).lng(25.94).speed(0f).bearing(0f)
                    .busNumber(null)  // ← null, але різний externalId
                    .online(true).build();

            when(vehicleRepository.findBySourceAndIsOnlineTrue(DataSource.transgps))
                    .thenReturn(Collections.emptyList());
            when(vehicleRepository.findByExternalIdAndSource(anyString(), any()))
                    .thenReturn(Optional.empty());
            when(sourceMappingRepository.findByEntityTypeAndSourceAndSourceId(any(), any(), any()))
                    .thenReturn(Optional.empty());
            when(routeRepository.findByNameAndType(any(), any())).thenReturn(Optional.empty());
            when(vehicleRepository.findNearbyFromOtherSource(anyDouble(), anyDouble(),
                    anyDouble(), any(), any())).thenReturn(Collections.emptyList());

            aggregationService.processPositions(List.of(dto1, dto2));

            // Різні ключі → обидва зберігаються (не дедуплікуються)
            verify(vehicleRepository, times(1)).save(argThat(v -> v.getExternalId().equals("ext-A")));
            verify(vehicleRepository, times(1)).save(argThat(v -> v.getExternalId().equals("ext-B")));
        }

        /**
         * Гілка: busNumber = "" (blank) → ключ по externalId.
         * !dto.getBusNumber().isBlank() → false
         */
        @Test
        @DisplayName("buildDeduplicationKey — busNumber=blank → ключ по externalId")
        void deduplicationKey_blankBusNumber_usesExternalId() {
            VehiclePositionDto dto = VehiclePositionDto.builder()
                    .externalId("ext-blank").source(DataSource.transgps)
                    .routeName("7").externalRouteId("r7").type(TransportType.BUS)
                    .lat(48.29).lng(25.93).speed(0f).bearing(0f)
                    .busNumber("  ")  // ← blank
                    .online(true).build();

            when(vehicleRepository.findBySourceAndIsOnlineTrue(DataSource.transgps))
                    .thenReturn(Collections.emptyList());
            when(vehicleRepository.findByExternalIdAndSource("ext-blank", DataSource.transgps))
                    .thenReturn(Optional.empty());
            when(sourceMappingRepository.findByEntityTypeAndSourceAndSourceId(any(), any(), any()))
                    .thenReturn(Optional.empty());
            when(routeRepository.findByNameAndType(any(), any())).thenReturn(Optional.empty());
            when(vehicleRepository.findNearbyFromOtherSource(anyDouble(), anyDouble(),
                    anyDouble(), any(), any())).thenReturn(Collections.emptyList());

            assertThatCode(() -> aggregationService.processPositions(List.of(dto)))
                    .doesNotThrowAnyException();
        }

        // ── processOne — busNumber null гілка ─────────────────────────────────

        /**
         * Гілка: dto.getBusNumber() != null → vehicle.setBusNumber() викликається.
         * Ця гілка вже покрита, але гілка busNumber=null → setBusNumber НЕ викликається.
         */
        @Test
        @DisplayName("processOne — busNumber=null → setBusNumber не викликається")
        void processOne_nullBusNumber_busNumberNotSet() {
            VehiclePositionDto dto = VehiclePositionDto.builder()
                    .externalId("ext-nobus").source(DataSource.nimbus)
                    .type(TransportType.BUS).lat(48.29).lng(25.93)
                    .speed(0f).bearing(0f).busNumber(null).online(true).build();

            Vehicle existing = buildVehicle(10L, "ext-nobus", DataSource.nimbus, true);
            existing.setBusNumber("OLD_NUMBER");
            when(vehicleRepository.findByExternalIdAndSource("ext-nobus", DataSource.nimbus))
                    .thenReturn(Optional.of(existing));

            aggregationService.processPositions(List.of(dto));

            // busNumber має залишитись "OLD_NUMBER" бо dto.getBusNumber() == null
            assertThat(existing.getBusNumber()).isEqualTo("OLD_NUMBER");
        }

        // ── deduplicateNearby — other.getIsOnline() = false гілка ────────────

        /**
         * Гілка: nearby vehicle вже офлайн → setIsOnline(false) НЕ викликається повторно.
         * if (other.getIsOnline()) { other.setIsOnline(false); ... }  ← гілка false
         */
        @Test
        @DisplayName("deduplicateNearby — nearby vehicle вже офлайн → save не викликається")
        void deduplicateNearby_nearbyAlreadyOffline_noSave() {
            Route route = buildRoute(1L, "10", TransportType.BUS);
            when(routeRepository.findByNameAndType("10", TransportType.BUS))
                    .thenReturn(Optional.of(route));
            when(sourceMappingRepository.findByEntityTypeAndSourceAndSourceId(any(), any(), any()))
                    .thenReturn(Optional.empty());
            when(vehicleRepository.findBySourceAndIsOnlineTrue(DataSource.easyway))
                    .thenReturn(Collections.emptyList());

            Vehicle existing = buildVehicle(5L, "ext-near", DataSource.easyway, true);
            when(vehicleRepository.findByExternalIdAndSource("ext-easyway", DataSource.easyway))
                    .thenReturn(Optional.of(existing));

            // Nearby vehicle вже офлайн (isOnline = false)
            Vehicle nearbyOffline = buildVehicle(99L, "nearby", DataSource.transgps, false);
            nearbyOffline.setRoute(route);
            when(vehicleRepository.findNearbyFromOtherSource(anyDouble(), anyDouble(),
                    anyDouble(), eq(DataSource.easyway), eq(route)))
                    .thenReturn(List.of(nearbyOffline));

            VehiclePositionDto dto = VehiclePositionDto.builder()
                    .externalId("ext-easyway").source(DataSource.easyway)
                    .routeName("10").externalRouteId("r10").type(TransportType.BUS)
                    .lat(48.29).lng(25.93).speed(0f).bearing(0f).busNumber("EW1")
                    .online(true).build();

            aggregationService.processPositions(List.of(dto));

            // nearbyOffline.save() не має викликатись — він вже офлайн
            verify(vehicleRepository, never()).save(argThat(v ->
                    v.getId() != null && v.getId().equals(99L)));
        }

        /**
         * Гілка: currentPriority > otherPriority → поточний vehicle йде офлайн
         * (низькопріоритетне джерело поступається высокопріоритетному).
         * else { current.setIsOnline(false); ... }
         */
        @Test
        @DisplayName("deduplicateNearby — нижчий пріоритет → поточний vehicle офлайн")
        void deduplicateNearby_lowerPriority_currentSetOffline() {
            Route route = buildRoute(1L, "10", TransportType.BUS);
            when(routeRepository.findByNameAndType("10", TransportType.BUS))
                    .thenReturn(Optional.of(route));
            when(sourceMappingRepository.findByEntityTypeAndSourceAndSourceId(any(), any(), any()))
                    .thenReturn(Optional.empty());
            // nimbus не запускає markAbsent
            when(vehicleRepository.findByExternalIdAndSource("ext-nimbus", DataSource.nimbus))
                    .thenReturn(Optional.empty());

            // nearby vehicle з easyway (пріоритет 1) — вищий ніж nimbus (пріоритет 3)
            Vehicle nearbyEasyway = buildVehicle(10L, "easy-1", DataSource.easyway, true);
            nearbyEasyway.setRoute(route);
            when(vehicleRepository.findNearbyFromOtherSource(anyDouble(), anyDouble(),
                    anyDouble(), eq(DataSource.nimbus), eq(route)))
                    .thenReturn(List.of(nearbyEasyway));

            VehiclePositionDto dto = VehiclePositionDto.builder()
                    .externalId("ext-nimbus").source(DataSource.nimbus)  // пріоритет 3
                    .routeName("10").externalRouteId("r10").type(TransportType.BUS)
                    .lat(48.29).lng(25.93).speed(0f).bearing(0f).busNumber("NIM1")
                    .online(true).build();

            aggregationService.processPositions(List.of(dto));

            // Поточний (nimbus) має стати офлайн бо easyway має вищий пріоритет
            ArgumentCaptor<Vehicle> captor = ArgumentCaptor.forClass(Vehicle.class);
            verify(vehicleRepository, atLeastOnce()).save(captor.capture());
            boolean anySetOffline = captor.getAllValues().stream()
                    .anyMatch(v -> v.getExternalId().equals("ext-nimbus") && !v.getIsOnline());
            assertThat(anySetOffline).isTrue();
        }

        // ── resolveRoute — mapping з type mismatch (found != null але type ≠) ─

        /**
         * Гілка: mapping знайдено, route знайдено, але type не збігається →
         * mapping видаляється і йде далі по логіці.
         * if (found != null && found.getType() == type) → false (type mismatch)
         */
        @Test
        @DisplayName("resolveRoute — mapping знайдено але type mismatch → mapping видаляється")
        void resolveRoute_mappingTypeMismatch_mappingDeleted() {
            // Route у БД має тип TROLL, але dto передає BUS
            Route trollRoute = buildRoute(5L, "3", TransportType.TROLL);
            SourceMapping mapping = new SourceMapping();
            mapping.setId(1L);
            mapping.setCanonicalId(5L);

            when(sourceMappingRepository.findByEntityTypeAndSourceAndSourceId(
                    "route", "transgps", "r3"))
                    .thenReturn(Optional.of(mapping));
            when(routeRepository.findById(5L)).thenReturn(Optional.of(trollRoute));
            // Після видалення mapping — шукає по name+type
            when(routeRepository.findByNameAndType("3", TransportType.BUS))
                    .thenReturn(Optional.empty());
            when(vehicleRepository.findBySourceAndIsOnlineTrue(DataSource.transgps))
                    .thenReturn(Collections.emptyList());
            when(vehicleRepository.findByExternalIdAndSource(any(), any()))
                    .thenReturn(Optional.empty());
            when(vehicleRepository.findNearbyFromOtherSource(anyDouble(), anyDouble(),
                    anyDouble(), any(), any())).thenReturn(Collections.emptyList());

            VehiclePositionDto dto = VehiclePositionDto.builder()
                    .externalId("ext-type").source(DataSource.transgps)
                    .routeName("3").externalRouteId("r3")
                    .type(TransportType.BUS)  // ← BUS, але в БД TROLL
                    .lat(48.29).lng(25.93).speed(0f).bearing(0f).online(true).build();

            aggregationService.processPositions(List.of(dto));

            // Старий mapping має бути видалений
            verify(sourceMappingRepository).delete(mapping);
        }

        /**
         * Гілка: mapping знайдено, але routeRepository.findById повертає null (deleted route).
         * if (found != null ...) → false (found == null)
         */
        @Test
        @DisplayName("resolveRoute — mapping є але route в БД не знайдено → mapping видаляється")
        void resolveRoute_mappingFoundButRouteDeleted_mappingDeleted() {
            SourceMapping mapping = new SourceMapping();
            mapping.setId(2L);
            mapping.setCanonicalId(999L);

            when(sourceMappingRepository.findByEntityTypeAndSourceAndSourceId(
                    "route", "transgps", "r999"))
                    .thenReturn(Optional.of(mapping));
            when(routeRepository.findById(999L)).thenReturn(Optional.empty()); // route видалено
            when(routeRepository.findByNameAndType("X", TransportType.BUS))
                    .thenReturn(Optional.empty());
            when(vehicleRepository.findBySourceAndIsOnlineTrue(DataSource.transgps))
                    .thenReturn(Collections.emptyList());
            when(vehicleRepository.findByExternalIdAndSource(any(), any()))
                    .thenReturn(Optional.empty());
            when(vehicleRepository.findNearbyFromOtherSource(anyDouble(), anyDouble(),
                    anyDouble(), any(), any())).thenReturn(Collections.emptyList());

            VehiclePositionDto dto = VehiclePositionDto.builder()
                    .externalId("ext-del").source(DataSource.transgps)
                    .routeName("X").externalRouteId("r999").type(TransportType.BUS)
                    .lat(48.29).lng(25.93).speed(0f).bearing(0f).online(true).build();

            aggregationService.processPositions(List.of(dto));

            verify(sourceMappingRepository).delete(mapping);
        }

        /**
         * Гілка: dto.getRouteName() == null але externalRouteId є →
         * route name стає "id:externalRouteId".
         * String name = dto.getRouteName() != null ? dto.getRouteName() : "id:" + dto.getExternalRouteId();
         */
        @Test
        @DisplayName("resolveRoute — routeName=null, externalRouteId є → name='id:routeId'")
        void resolveRoute_nullRouteName_nameFromExternalRouteId() {
            when(sourceMappingRepository.findByEntityTypeAndSourceAndSourceId(any(), any(), any()))
                    .thenReturn(Optional.empty());
            when(routeRepository.findByNameAndType(any(), any())).thenReturn(Optional.empty());
            when(vehicleRepository.findBySourceAndIsOnlineTrue(DataSource.transgps))
                    .thenReturn(Collections.emptyList());
            when(vehicleRepository.findByExternalIdAndSource(any(), any()))
                    .thenReturn(Optional.empty());
            when(vehicleRepository.findNearbyFromOtherSource(anyDouble(), anyDouble(),
                    anyDouble(), any(), any())).thenReturn(Collections.emptyList());

            VehiclePositionDto dto = VehiclePositionDto.builder()
                    .externalId("ext-noname").source(DataSource.transgps)
                    .routeName(null)          // ← null
                    .externalRouteId("r42")   // ← є
                    .type(TransportType.BUS)
                    .lat(48.29).lng(25.93).speed(0f).bearing(0f).online(true).build();

            aggregationService.processPositions(List.of(dto));

            ArgumentCaptor<Route> captor = ArgumentCaptor.forClass(Route.class);
            verify(routeRepository).save(captor.capture());
            assertThat(captor.getValue().getName()).isEqualTo("id:r42");
        }

        /**
         * Гілка: race condition при створенні маршруту → save кидає exception →
         * catch → findByNameAndType повертає існуючий маршрут.
         */
        @Test
        @DisplayName("resolveRoute — race condition при save → catch → findByNameAndType")
        void resolveRoute_raceConditionOnSave_findsExistingRoute() {
            Route existingRoute = buildRoute(77L, "race", TransportType.BUS);

            // Очищаємо попередні налаштування для routeRepository.save
            reset(routeRepository);

            when(sourceMappingRepository.findByEntityTypeAndSourceAndSourceId(any(), any(), any()))
                    .thenReturn(Optional.empty());

            // Перший виклик findByNameAndType — порожній, другий — знаходить route
            when(routeRepository.findByNameAndType("race", TransportType.BUS))
                    .thenReturn(Optional.empty())            // перший виклик — нема
                    .thenReturn(Optional.of(existingRoute)); // другий — після race condition

            // Save кидає exception (симулюємо race condition)
            when(routeRepository.save(any(Route.class)))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));

            when(vehicleRepository.findBySourceAndIsOnlineTrue(DataSource.transgps))
                    .thenReturn(Collections.emptyList());
            when(vehicleRepository.findByExternalIdAndSource(any(), any()))
                    .thenReturn(Optional.empty());
            when(vehicleRepository.findNearbyFromOtherSource(anyDouble(), anyDouble(),
                    anyDouble(), any(), any())).thenReturn(Collections.emptyList());

            VehiclePositionDto dto = VehiclePositionDto.builder()
                    .externalId("ext-race").source(DataSource.transgps)
                    .routeName("race").externalRouteId("r-race").type(TransportType.BUS)
                    .lat(48.29).lng(25.93).speed(0f).bearing(0f).online(true).build();

            // Має відновитись і знайти існуючий route без exception назовні
            assertThatCode(() -> aggregationService.processPositions(List.of(dto)))
                    .doesNotThrowAnyException();

            // Перевіряємо, що save викликався один раз
            verify(routeRepository, times(1)).save(any(Route.class));
            // Перевіряємо, що findByNameAndType викликався двічі
            verify(routeRepository, times(2)).findByNameAndType("race", TransportType.BUS);
        }

        // ── Helpers ───────────────────────────────────────────────────────────

        private VehiclePositionDto buildDto(String externalId, DataSource source,
                                            String routeName, String externalRouteId) {
            return VehiclePositionDto.builder()
                    .externalId(externalId).source(source)
                    .routeName(routeName).externalRouteId(externalRouteId)
                    .type(TransportType.BUS).lat(48.29).lng(25.93)
                    .speed(30f).bearing(90f).busNumber("1234").online(true).build();
        }

        private Vehicle buildVehicle(Long id, String externalId, DataSource source, boolean online) {
            Vehicle v = new Vehicle();
            v.setId(id);
            v.setExternalId(externalId);
            v.setSource(source);
            v.setLat(48.29);
            v.setLng(25.93);
            v.setIsOnline(online);
            v.setLastSeen(OffsetDateTime.now());
            return v;
        }

        private Route buildRoute(Long id, String name, TransportType type) {
            Route r = new Route();
            r.setId(id);
            r.setName(name);
            r.setType(type);
            r.setIsActive(true);
            r.setUpdatedAt(OffsetDateTime.now());
            return r;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // AdminService
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AdminService — branch coverage")
    class AdminServiceBranchTests {

        @Mock VehicleRepository vehicleRepository;
        @Mock RouteRepository routeRepository;
        @Mock GpsHistoryRepository gpsHistoryRepository;
        @Mock SourceMappingRepository sourceMappingRepository;

        AdminService adminService;

        @BeforeEach
        void setUp() {
            adminService = new AdminService(vehicleRepository, routeRepository,
                    gpsHistoryRepository, sourceMappingRepository);
        }

        // ── getAllRoutes — vehicle без route (getRoute() == null) ─────────────

        /**
         * Гілка у getAllRoutes():
         * .filter(v -> v.getRoute() != null)  → false якщо route == null
         * Vehicles без маршруту не мають враховуватись у vehicleCount.
         */
        @Test
        @DisplayName("getAllRoutes — vehicle без route → не рахується у vehicleCount")
        void getAllRoutes_vehicleWithoutRoute_notCounted() {
            Route route = buildRoute(1L, "10", TransportType.BUS);

            Vehicle withRoute    = buildVehicle(1L, route, true);
            Vehicle withoutRoute = buildVehicle(2L, null, true);  // route = null

            when(routeRepository.findAll()).thenReturn(List.of(route));
            when(vehicleRepository.findByIsOnlineTrue()).thenReturn(List.of(withRoute, withoutRoute));

            List<AdminRouteResponseDto> result = adminService.getAllRoutes();

            // Тільки withRoute рахується
            assertThat(result.get(0).getVehicleCount()).isEqualTo(1);
        }

        // ── createRoute — типи TRAM, TAXI, DEFAULT ────────────────────────────

        /**
         * parseType() — гілки для TRAM, TAXI, DEFAULT.
         * Ці значення є у enum але не тестувались раніше.
         */
        @Test
        @DisplayName("createRoute — тип TRAM → зберігається правильно")
        void createRoute_tramType_savedCorrectly() {
            AdminRouteRequestDto req = new AdminRouteRequestDto();
            req.setName("T1"); req.setType("TRAM");

            Route saved = buildRoute(1L, "T1", TransportType.TRAM);
            when(routeRepository.save(any())).thenReturn(saved);

            AdminRouteResponseDto result = adminService.createRoute(req);

            assertThat(result.getType()).isEqualTo("TRAM");
            ArgumentCaptor<Route> captor = ArgumentCaptor.forClass(Route.class);
            verify(routeRepository).save(captor.capture());
            assertThat(captor.getValue().getType()).isEqualTo(TransportType.TRAM);
        }

        @Test
        @DisplayName("createRoute — тип TAXI → зберігається правильно")
        void createRoute_taxiType_savedCorrectly() {
            AdminRouteRequestDto req = new AdminRouteRequestDto();
            req.setName("TX"); req.setType("TAXI");

            Route saved = buildRoute(2L, "TX", TransportType.TAXI);
            when(routeRepository.save(any())).thenReturn(saved);

            AdminRouteResponseDto result = adminService.createRoute(req);
            assertThat(result.getType()).isEqualTo("TAXI");
        }

        @Test
        @DisplayName("createRoute — тип DEFAULT → зберігається правильно")
        void createRoute_defaultType_savedCorrectly() {
            AdminRouteRequestDto req = new AdminRouteRequestDto();
            req.setName("D1"); req.setType("DEFAULT");

            Route saved = buildRoute(3L, "D1", TransportType.DEFAULT);
            when(routeRepository.save(any())).thenReturn(saved);

            AdminRouteResponseDto result = adminService.createRoute(req);
            assertThat(result.getType()).isEqualTo("DEFAULT");
        }

        // ── updateRoute — часткове оновлення (patch-семантика) ───────────────

        /**
         * Гілки у updateRoute():
         * if (req.getName() != null)   → false (null) — поле не змінюється
         * if (req.getType() != null)   → false (null)
         * if (req.getColor() != null)  → false (null)
         * if (req.getIsActive() != null) → false (null)
         * Всі чотири гілки "null" не покривались.
         */
        @Test
        @DisplayName("updateRoute — всі поля null (patch) → нічого не змінюється")
        void updateRoute_allFieldsNull_nothingChanged() {
            Route existing = buildRoute(1L, "10", TransportType.BUS);
            existing.setColor("#FF0000");
            existing.setIsActive(true);

            when(routeRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(routeRepository.save(any())).thenReturn(existing);
            when(vehicleRepository.findByRouteIdAndIsOnlineTrue(1L))
                    .thenReturn(Collections.emptyList());

            AdminRouteRequestDto req = new AdminRouteRequestDto();
            // Всі поля null — нічого не передаємо

            adminService.updateRoute(1L, req);

            // Поля мають залишитись незмінними
            assertThat(existing.getName()).isEqualTo("10");
            assertThat(existing.getType()).isEqualTo(TransportType.BUS);
            assertThat(existing.getColor()).isEqualTo("#FF0000");
            assertThat(existing.getIsActive()).isTrue();
        }

        @Test
        @DisplayName("updateRoute — тільки name оновлюється, решта null")
        void updateRoute_onlyNameUpdated_othersUnchanged() {
            Route existing = buildRoute(1L, "10", TransportType.BUS);
            existing.setColor("#FF0000");

            when(routeRepository.findById(1L)).thenReturn(Optional.of(existing));
            when(routeRepository.save(any())).thenReturn(existing);
            when(vehicleRepository.findByRouteIdAndIsOnlineTrue(1L))
                    .thenReturn(Collections.emptyList());

            AdminRouteRequestDto req = new AdminRouteRequestDto();
            req.setName("10A");
            // type, color, isActive = null

            adminService.updateRoute(1L, req);

            assertThat(existing.getName()).isEqualTo("10A");
            assertThat(existing.getType()).isEqualTo(TransportType.BUS);  // не змінився
            assertThat(existing.getColor()).isEqualTo("#FF0000");          // не змінився
        }

        // ── getCollectorsStatus — lastSeen вибирає максимальний ──────────────

        /**
         * Гілка у getCollectorsStatus():
         * (a, b) -> a.isAfter(b) ? a : b  → обидві гілки (a > b і b > a)
         * Потрібні два vehicles від одного джерела з різними lastSeen.
         */
        @Test
        @DisplayName("getCollectorsStatus — два vehicles від джерела → вибирається новіший lastSeen")
        void getCollectorsStatus_twoVehicles_latestLastSeenChosen() {
            OffsetDateTime older = OffsetDateTime.now().minusHours(2);
            OffsetDateTime newer = OffsetDateTime.now().minusMinutes(5);

            Vehicle v1 = buildVehicle(1L, null, true);
            v1.setSource(DataSource.transgps);
            v1.setLastSeen(older);

            Vehicle v2 = buildVehicle(2L, null, true);
            v2.setSource(DataSource.transgps);
            v2.setLastSeen(newer);

            when(vehicleRepository.findByIsOnlineTrue()).thenReturn(List.of(v1, v2));

            List<CollectorStatusDto> result = adminService.getCollectorsStatus();

            CollectorStatusDto transgpsStatus = result.stream()
                    .filter(s -> s.getSource().equals("transgps"))
                    .findFirst().orElseThrow();

            // Має бути newer (максимальний)
            assertThat(transgpsStatus.getLastReceivedAt()).isEqualTo(newer);
        }

        // ── getMappings — фільтр source case-insensitive ──────────────────────

        /**
         * Гілка: .filter(m -> source == null || m.getSource().name().equalsIgnoreCase(source))
         * source != null і equalsIgnoreCase → true (збігається) і false (не збігається).
         */
        @Test
        @DisplayName("getMappings — source фільтр — не збігається → виключається")
        void getMappings_sourceFilter_nonMatchingExcluded() {
            SourceMapping m1 = buildMapping(1L, "route", 10L, DataSource.transgps, "r1");
            SourceMapping m2 = buildMapping(2L, "route", 11L, DataSource.easyway, "r2");

            when(sourceMappingRepository.findAll()).thenReturn(List.of(m1, m2));

            List<SourceMappingResponseDto> result = adminService.getMappings("easyway", null);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getSource()).isEqualTo("easyway");
        }

        @Test
        @DisplayName("getMappings — обидва фільтри одночасно")
        void getMappings_bothFilters_narrowsResults() {
            SourceMapping m1 = buildMapping(1L, "route",   10L, DataSource.transgps, "r1");
            SourceMapping m2 = buildMapping(2L, "stop",    20L, DataSource.transgps, "s1");
            SourceMapping m3 = buildMapping(3L, "route",   11L, DataSource.nimbus,   "r2");

            when(sourceMappingRepository.findAll()).thenReturn(List.of(m1, m2, m3));

            List<SourceMappingResponseDto> result =
                    adminService.getMappings("transgps", "route");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getSourceId()).isEqualTo("r1");
        }

        // ── Helpers ───────────────────────────────────────────────────────────

        private Route buildRoute(Long id, String name, TransportType type) {
            Route r = new Route();
            r.setId(id); r.setName(name); r.setType(type);
            r.setColor("#000000"); r.setIsActive(true);
            r.setUpdatedAt(OffsetDateTime.now());
            return r;
        }

        private Vehicle buildVehicle(Long id, Route route, boolean online) {
            Vehicle v = new Vehicle();
            v.setId(id);
            v.setExternalId("ext-" + id);
            v.setSource(DataSource.transgps);
            v.setRoute(route);
            v.setLat(48.0); v.setLng(25.0);
            v.setIsOnline(online);
            v.setLastSeen(OffsetDateTime.now());
            return v;
        }

        private SourceMapping buildMapping(Long id, String entityType, Long canonicalId,
                                           DataSource source, String sourceId) {
            SourceMapping m = new SourceMapping();
            m.setId(id); m.setEntityType(entityType);
            m.setCanonicalId(canonicalId); m.setSource(source); m.setSourceId(sourceId);
            return m;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // VehicleBroadcastService
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("VehicleBroadcastService — branch coverage")
    class VehicleBroadcastServiceBranchTests {

        @Mock SimpMessagingTemplate messagingTemplate;
        @Mock VehicleRepository vehicleRepository;

        VehicleBroadcastService broadcastService;

        @BeforeEach
        void setUp() {
            broadcastService = new VehicleBroadcastService(messagingTemplate, vehicleRepository);
        }

        /**
         * Гілка: filter(e -> e.getRouteName() != null) → false для vehicle без маршруту.
         * Vehicle без route → routeName == null → НЕ надсилається в /topic/routes/{name}.
         */
        @Test
        @DisplayName("broadcast — vehicle без routeName → не надсилається в route topic")
        void broadcast_vehicleWithoutRoute_notSentToRouteTopic() {
            Vehicle noRoute = buildVehicle(1L, null);
            Vehicle withRoute = buildVehicle(2L, "10");

            when(vehicleRepository.findByIsOnlineTrue()).thenReturn(List.of(noRoute, withRoute));

            broadcastService.broadcast(List.of(noRoute));

            // /topic/routes/10 має бути - використовуємо anyList() замість any()
            verify(messagingTemplate).convertAndSend(eq("/topic/routes/10"), anyList());
            // /topic/routes/null НЕ має бути
            verify(messagingTemplate, never()).convertAndSend(eq("/topic/routes/null"), anyList());
        }

        /**
         * Гілка: vehicles з різними маршрутами → forEach надсилає в кожен route topic.
         * Покриває groupingBy + forEach у broadcast().
         */
        @Test
        @DisplayName("broadcast — vehicles з різними маршрутами → окремі route topics")
        void broadcast_differentRoutes_separateTopics() {
            Vehicle v1 = buildVehicle(1L, "10");
            Vehicle v2 = buildVehicle(2L, "3");
            Vehicle v3 = buildVehicle(3L, "10"); // дублює маршрут 10

            when(vehicleRepository.findByIsOnlineTrue()).thenReturn(List.of(v1, v2, v3));

            broadcastService.broadcast(List.of(v1));

            verify(messagingTemplate).convertAndSend(eq("/topic/routes/10"), anyList());
            verify(messagingTemplate).convertAndSend(eq("/topic/routes/3"), anyList());
        }

        /**
         * Гілка: всі vehicles мають routeName → filter пропускає всіх,
         * forEach викликається для кожного унікального маршруту.
         */
        @Test
        @DisplayName("broadcast — тільки vehicles з routeName → filter пропускає всіх")
        void broadcast_allVehiclesHaveRoute_filterPassesAll() {
            Vehicle v1 = buildVehicle(1L, "5");
            Vehicle v2 = buildVehicle(2L, "7");

            when(vehicleRepository.findByIsOnlineTrue()).thenReturn(List.of(v1, v2));

            broadcastService.broadcast(List.of(v1));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<VehiclePositionEvent>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(messagingTemplate).convertAndSend(eq("/topic/vehicles"), captor.capture());
            assertThat(captor.getValue()).hasSize(2);

            // Обидва маршрути отримують свої топіки - використовуємо anyList()
            verify(messagingTemplate).convertAndSend(eq("/topic/routes/5"), anyList());
            verify(messagingTemplate).convertAndSend(eq("/topic/routes/7"), anyList());
        }

        /**
         * Гілка: toEvent() — vehicle.getRoute() == null →
         * routeName, type, color = null (три гілки null-check).
         */
        @Test
        @DisplayName("broadcast — toEvent() — vehicle без route → null поля у event")
        void broadcast_toEvent_vehicleWithoutRoute_nullFields() {
            Vehicle noRoute = buildVehicle(1L, null);
            when(vehicleRepository.findByIsOnlineTrue()).thenReturn(List.of(noRoute));

            broadcastService.broadcast(List.of(noRoute));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<VehiclePositionEvent>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(messagingTemplate).convertAndSend(eq("/topic/vehicles"), captor.capture());

            VehiclePositionEvent event = captor.getValue().get(0);
            assertThat(event.getRouteName()).isNull();
            assertThat(event.getType()).isNull();
            assertThat(event.getColor()).isNull();
        }

        // ── Helpers ───────────────────────────────────────────────────────────

        private Vehicle buildVehicle(Long id, String routeName) {
            Vehicle v = new Vehicle();
            v.setId(id);
            v.setLat(48.29); v.setLng(25.93);
            v.setSpeed(30f); v.setBearing(90f);
            v.setBusNumber("B" + id);
            v.setIsOnline(true);

            if (routeName != null) {
                Route route = new Route();
                route.setId(id * 10);
                route.setName(routeName);
                route.setType(TransportType.BUS);
                route.setColor("#1E88E5");
                v.setRoute(route);
            }
            return v;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // RouteService — evictCache() метод
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("RouteService — evictCache method coverage")
    class RouteServiceEvictCacheTests {

        @Mock RouteRepository routeRepository;
        @Mock VehicleRepository vehicleRepository;

        RouteService routeService;

        @BeforeEach
        void setUp() {
            routeService = new RouteService(routeRepository, vehicleRepository);
        }

        /**
         * evictCache() — метод з @CacheEvict, тіло порожнє.
         * Але JaCoCo рахує його як непокритий метод (66% = 2/3).
         * Просто викликаємо — це достатньо для method coverage.
         */
        @Test
        @DisplayName("evictCache — метод існує і не кидає exception")
        void evictCache_noException() {
            assertThatCode(() -> routeService.evictCache()).doesNotThrowAnyException();
        }
    }
}