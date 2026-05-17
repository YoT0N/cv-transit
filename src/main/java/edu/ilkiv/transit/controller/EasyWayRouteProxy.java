package edu.ilkiv.transit.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@RestController
@RequestMapping("/api/route")
@RequiredArgsConstructor
public class EasyWayRouteProxy {

    private static final String BASE = "https://www.eway.in.ua/ajax/ua/chernivtsi";
    private static final String UA   = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";

    private final WebClient webClient;

    @PostMapping("/compile")
    public ResponseEntity<String> compile(
            @RequestParam double start_lat,
            @RequestParam double start_lng,
            @RequestParam double stop_lat,
            @RequestParam double stop_lng,
            @RequestParam(defaultValue = "optimal") String way_type) {

        String body = String.format(
                "start_lat=%s&start_lng=%s&stop_lat=%s&stop_lng=%s" +
                        "&direct=false&way_type=%s&transports=bus%%2Ctrol&enable_walk_ways=0",
                start_lat, start_lng, stop_lat, stop_lng, way_type);

        String response = webClient.post()
                .uri(BASE + "/compile")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("Referer",  "https://www.eway.in.ua/")
                .header("Origin",   "https://www.eway.in.ua")
                .header("User-Agent", UA)
                .header("X-Requested-With", "XMLHttpRequest")
                .body(BodyInserters.fromValue(body))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }

    @PostMapping("/compile-route")
    public ResponseEntity<String> compileRoute(@RequestBody String body) {
        String response = webClient.post()
                .uri(BASE + "/getCompileRoute")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("Referer",  "https://www.eway.in.ua/")
                .header("Origin",   "https://www.eway.in.ua")
                .header("User-Agent", UA)
                .header("X-Requested-With", "XMLHttpRequest")
                .body(BodyInserters.fromValue(body))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }
}