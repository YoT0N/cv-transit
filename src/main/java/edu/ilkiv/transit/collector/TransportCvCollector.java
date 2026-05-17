package edu.ilkiv.transit.collector;

import edu.ilkiv.transit.dto.TransportCvResponseDto;
import edu.ilkiv.transit.dto.TransportCvVehicleDto;
import edu.ilkiv.transit.dto.VehiclePositionDto;
import edu.ilkiv.transit.model.DataSource;
import edu.ilkiv.transit.model.TransportType;
import edu.ilkiv.transit.service.VehicleAggregationService;
import edu.ilkiv.transit.util.TransportCvSignature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Збирає позиції транспорту з transport.cv.ua кожні 30 секунд.
 *
 * rtsId → routeName маппінг захардкоджений до тих пір поки не знайдемо
 * офіційний API маршрутів transport.cv.ua.
 * Додавати нові маршрути: просто додай рядок у ROUTE_NAMES.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "transportcv.collector.enabled", havingValue = "true", matchIfMissing = false)
public class TransportCvCollector {

    private static final String URL = "https://transport.cv.ua/api/positions";

    private static final String REFERER     = "https://transport.cv.ua/";
    private static final String ORIGIN      = "https://transport.cv.ua";
    private static final String ACCEPT      = "application/json, text/plain, */*";
    private static final String ACCEPT_LANG = "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7";

    /**
     * rtsId (з поля rtsId у відповіді transport.cv.ua) → людська назва маршруту.
     * TODO: замінити на динамічне завантаження коли знайдемо API маршрутів.
     */
    private static final Map<Long, String> ROUTE_NAMES = Map.of(
            121L,  "3",
            823L,  "21",
            1922L, "26",
            1942L, "37",
            842L, "33"
    );

    private final WebClient webClient;
    private final VehicleAggregationService aggregationService;

    @Scheduled(fixedDelay = 30_000, initialDelay = 5_000)
    public void collect() {
        log.debug("TransportCvCollector: fetching...");
        try {
            String reqDate = TransportCvSignature.buildReqDate();
            String sign    = TransportCvSignature.buildSign(reqDate, TransportCvSignature.USER_AGENT);

            log.debug("TransportCvCollector -> Reqdate: [{}]", reqDate);
            log.debug("TransportCvCollector -> Sign:    [{}]", sign);

            TransportCvResponseDto response = webClient
                    .mutate()
                    .filter(forceUserAgent(TransportCvSignature.USER_AGENT))
                    .build()
                    .post()
                    .uri(URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Reqdate",               reqDate)
                    .header("Sign",                  sign)
                    .header(HttpHeaders.USER_AGENT,  TransportCvSignature.USER_AGENT)
                    .header(HttpHeaders.REFERER,     REFERER)
                    .header(HttpHeaders.ORIGIN,      ORIGIN)
                    .header(HttpHeaders.ACCEPT,      ACCEPT)
                    .header("Accept-Language",       ACCEPT_LANG)
                    .header("Sec-Fetch-Dest", "empty")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Site", "same-origin")
                    .bodyValue("{\"routeIds\":[]}")
                    .exchangeToMono(resp -> {
                        if (resp.statusCode().is2xxSuccessful()) {
                            return resp.bodyToMono(TransportCvResponseDto.class);
                        }
                        return resp.bodyToMono(String.class)
                                .flatMap(body -> {
                                    log.warn("TransportCvCollector: {} — body: {}",
                                            resp.statusCode(), body);
                                    return Mono.empty();
                                });
                    })
                    .block();

            if (response == null || response.getTransports() == null
                    || response.getTransports().isEmpty()) {
                log.warn("TransportCvCollector: empty or null response");
                return;
            }

            List<VehiclePositionDto> positions = response.getTransports().stream()
                    .filter(v -> v.getLat() != null && v.getLon() != null)
                    .map(this::toPositionDto)
                    .toList();

            log.debug("TransportCvCollector: got {} vehicles", positions.size());
            aggregationService.processPositions(positions);

        } catch (Exception e) {
            log.error("TransportCvCollector: exception — {}", e.getMessage(), e);
        }
    }

    private static ExchangeFilterFunction forceUserAgent(String userAgent) {
        return ExchangeFilterFunction.ofRequestProcessor(request -> {
            ClientRequest modified = ClientRequest.from(request)
                    .headers(h -> h.set(HttpHeaders.USER_AGENT, userAgent))
                    .build();
            return Mono.just(modified);
        });
    }

    private VehiclePositionDto toPositionDto(TransportCvVehicleDto dto) {
        String routeName = ROUTE_NAMES.get(dto.getRtsId());

        if (routeName == null) {
            // Невідомий маршрут — логуємо щоб можна було додати у маппінг
            log.info("TransportCvCollector: unknown rtsId={}, transportNumber={} — add to ROUTE_NAMES",
                    dto.getRtsId(), dto.getTransportNumber());
        }

        return VehiclePositionDto.builder()
                .externalId(String.valueOf(dto.getId()))
                .source(DataSource.transportcv)
                .externalRouteId(String.valueOf(dto.getRtsId()))
                .routeName(routeName)   // null якщо rtsId не в маппінгу
                .type(TransportType.BUS)
                .lat(dto.getLat())
                .lng(dto.getLon())
                .speed(dto.getSpeed() != null ? dto.getSpeed().floatValue() : 0f)
                .bearing(dto.getAngle() != null ? dto.getAngle().floatValue() : 0f)
                .busNumber(dto.getTransportNumber())
                .online(true)
                .build();
    }
}