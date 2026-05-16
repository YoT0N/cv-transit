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

/**
 * Збирає позиції транспорту з transport.cv.ua кожні 30 секунд.
 *
 * Підпис: SHA-1(SALT + reqDate + SALT + userAgent + SALT)
 * ВАЖЛИВО: User-Agent у заголовку запиту МУСИТЬ збігатися з тим,
 * що використовувався при обчисленні підпису.
 * Reactor Netty за замовчуванням додає свій User-Agent — тому
 * ми явно перезаписуємо його через ExchangeFilterFunction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "transportcv.collector.enabled", havingValue = "true", matchIfMissing = false)
public class TransportCvCollector {

    private static final String URL = "https://transport.cv.ua/api/positions";

    private final WebClient webClient;
    private final VehicleAggregationService aggregationService;

    // Тимчасова заміна методу collect() у TransportCvCollector
// Додає onStatus щоб прочитати тіло 400 відповіді

    @Scheduled(fixedDelay = 30_000, initialDelay = 5_000)
    public void collect() {
        log.debug("TransportCvCollector: fetching...");
        try {
            String reqDate = TransportCvSignature.buildReqDate();
            String sign    = TransportCvSignature.buildSign(reqDate, TransportCvSignature.USER_AGENT);

            log.debug("TransportCvCollector -> Reqdate: [{}]", reqDate);
            log.debug("TransportCvCollector -> Sign:    [{}]", sign);

            // Читаємо RAW відповідь — включно з тілом помилки
            String rawBody = webClient
                    .mutate()
                    .filter(forceUserAgent(TransportCvSignature.USER_AGENT))
                    .build()
                    .post()
                    .uri(URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Reqdate",              reqDate)
                    .header("Sign",                 sign)
                    .header(HttpHeaders.USER_AGENT, TransportCvSignature.USER_AGENT)
                    .bodyValue("{\"routeIds\":[]}")
                    .exchangeToMono(response -> {
                        // Логуємо статус і ВСІ заголовки відповіді
                        log.warn("TransportCvCollector response status: {}", response.statusCode());
                        log.warn("TransportCvCollector response headers: {}", response.headers().asHttpHeaders());
                        // Повертаємо тіло як рядок незалежно від статусу
                        return response.bodyToMono(String.class);
                    })
                    .block();

            // Виводимо тіло відповіді — тут буде причина помилки
            log.warn("TransportCvCollector response body: [{}]", rawBody);

        } catch (Exception e) {
            log.warn("TransportCvCollector: exception — {}", e.getMessage(), e);
        }
    }

    /**
     * ExchangeFilterFunction що перезаписує User-Agent вже після того,
     * як Reactor Netty міг встановити свій дефолтний ("ReactorNetty/x.x.x").
     * Гарантує що реально надісланий заголовок збігається з тим що пішов у SHA-1.
     */
    private static ExchangeFilterFunction forceUserAgent(String userAgent) {
        return ExchangeFilterFunction.ofRequestProcessor(request -> {
            log.debug("TransportCvCollector actual headers: {}",
                    request.headers().entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .toList());

            ClientRequest modified = ClientRequest.from(request)
                    .headers(h -> h.set(HttpHeaders.USER_AGENT, userAgent))
                    .build();
            return Mono.just(modified);
        });
    }

    private VehiclePositionDto toPositionDto(TransportCvVehicleDto dto) {
        return VehiclePositionDto.builder()
                .externalId(String.valueOf(dto.getId()))
                .source(DataSource.transportcv)
                .externalRouteId(String.valueOf(dto.getRtsId()))
                .type(TransportType.BUS)
                .lat(dto.getLat())
                .lng(dto.getLon())
                .speed(dto.getSpeed() != null ? dto.getSpeed().floatValue() : 0f)
                .bearing(dto.getAngle() != null ? dto.getAngle().floatValue() : 0f)
                .busNumber(dto.getTransportNumber())
                .online(!"stay".equals(dto.getStatusName()))
                .build();
    }
}