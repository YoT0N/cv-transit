package edu.ilkiv.transit.collector;

import edu.ilkiv.transit.dto.VehiclePositionDto;
import edu.ilkiv.transit.model.DataSource;
import edu.ilkiv.transit.model.TransportType;
import edu.ilkiv.transit.service.VehicleAggregationService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Підключається до WebSocket EasyWay і отримує позиції транспорту Чернівців.
 *
 * Протокол: WSS (WebSocket Secure)
 * URL: wss://ws.easyway.info/sub/gps/{token}
 * Формат повідомлення:
 *   1. Текстовий фрейм — base64-рядок
 *   2. Base64 декодується → зашифровані байти
 *   3. AES-128-CBC розшифрування (ключ = IV = "k38hdGen3ksqAe3m")
 *   4. Результат — Google Protocol Buffers (бінарний)
 *
 * Структура protobuf (reverse-engineered з браузера):
 *   field 1 (varint)  → timestamp
 *   field 2 (message) → repeated RouteEntry
 *     field 1 (varint)  → routeId
 *     field 2 (message) → repeated VehiclePosition
 *       field 7  (string) → "075(6351)" — маршрут(бортовий)
 *       field 8  (varint) → angle (bearing)
 *       field 12 (varint) → speed
 *       field 15 (varint) → lat * 1_000_000
 *       field 16 (varint) → lng * 1_000_000
 *
 * Токен: 52822d67a2fb3376bbed239d7c0ca7ae (публічний, для Чернівців)
 * AES ключ/IV: k38hdGen3ksqAe3m (витягнуто з JS сайту eway.in.ua)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "easyway.collector.enabled", havingValue = "true", matchIfMissing = false)
public class EasyWayCollector implements WebSocket.Listener {

    private static final String WS_URL =
            "wss://ws.easyway.info/sub/gps/52822d67a2fb3376bbed239d7c0ca7ae";

    // AES-128-CBC: ключ і IV однакові — витягнуто з JS функції ddt на eway.in.ua
    private static final byte[] AES_KEY = "k38hdGen3ksqAe3m".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AES_IV  = "k38hdGen3ksqAe3m".getBytes(StandardCharsets.UTF_8);

    private static final long RECONNECT_DELAY_SEC = 10;

    // Координатні межі Чернівців (для валідації)
    private static final double LAT_MIN = 47.5, LAT_MAX = 49.0;
    private static final double LNG_MIN = 24.5, LNG_MAX = 27.0;

    // Буфер для збору фрагментованих текстових повідомлень
    private final StringBuilder textBuffer = new StringBuilder();

    /**
     * Маппінг EasyWay routeId → назва маршруту.
     * Отримано з window._cityRoutes.routes на eway.in.ua/ua/cities/chernivtsi
     */
    private static final Map<Integer, String> ROUTE_NAMES = Map.ofEntries(
            Map.entry(1,  "1"),
            Map.entry(2,  "2"),
            Map.entry(3,  "4"),
            Map.entry(4,  "3"),
            Map.entry(6,  "6A"),
            Map.entry(7,  "6"),
            Map.entry(8,  "5"),
            Map.entry(9,  "3"),
            Map.entry(10, "4"),
            Map.entry(11, "6"),
            Map.entry(14, "26"),
            Map.entry(15, "29"),
            Map.entry(16, "30"),
            Map.entry(18, "31"),
            Map.entry(20, "5"),
            Map.entry(21, "8"),
            Map.entry(22, "9A"),
            Map.entry(23, "10A"),
            Map.entry(25, "13"),
            Map.entry(26, "15"),
            Map.entry(27, "15К"),
            Map.entry(30, "19"),
            Map.entry(32, "21"),
            Map.entry(34, "23"),
            Map.entry(36, "27"),
            Map.entry(38, "36"),
            Map.entry(39, "37"),
            Map.entry(40, "32"),
            Map.entry(41, "34"),
            Map.entry(44, "39"),
            Map.entry(45, "1"),
            Map.entry(48, "41"),
            Map.entry(52, "43"),
            Map.entry(53, "33"),
            Map.entry(55, "7"),
            Map.entry(56, "14"),
            Map.entry(57, "25"),
            Map.entry(58, "24"),
            Map.entry(60, "10"),
            Map.entry(61, "20"),
            Map.entry(62, "35"),
            Map.entry(75, "8"),
            Map.entry(81, "8А"),
            Map.entry(82, "9"),
            Map.entry(83, "35A")
    );

    private static final Map<Integer, TransportType> ROUTE_TYPES = Map.ofEntries(
            Map.entry(1,  TransportType.TROLL),
            Map.entry(2,  TransportType.TROLL),
            Map.entry(3,  TransportType.TROLL),
            Map.entry(4,  TransportType.TROLL),
            Map.entry(6,  TransportType.TROLL),
            Map.entry(7,  TransportType.TROLL),
            Map.entry(8,  TransportType.TROLL),
            Map.entry(75, TransportType.TROLL),
            Map.entry(9,  TransportType.BUS),
            Map.entry(10, TransportType.BUS),
            Map.entry(11, TransportType.BUS),
            Map.entry(14, TransportType.BUS),
            Map.entry(15, TransportType.BUS),
            Map.entry(16, TransportType.BUS),
            Map.entry(18, TransportType.BUS),
            Map.entry(20, TransportType.BUS),
            Map.entry(21, TransportType.BUS),
            Map.entry(22, TransportType.BUS),
            Map.entry(23, TransportType.BUS),
            Map.entry(25, TransportType.BUS),
            Map.entry(26, TransportType.BUS),
            Map.entry(27, TransportType.BUS),
            Map.entry(30, TransportType.BUS),
            Map.entry(32, TransportType.BUS),
            Map.entry(34, TransportType.BUS),
            Map.entry(36, TransportType.BUS),
            Map.entry(38, TransportType.BUS),
            Map.entry(39, TransportType.BUS),
            Map.entry(40, TransportType.BUS),
            Map.entry(41, TransportType.BUS),
            Map.entry(44, TransportType.BUS),
            Map.entry(45, TransportType.BUS),
            Map.entry(48, TransportType.BUS),
            Map.entry(52, TransportType.BUS),
            Map.entry(53, TransportType.BUS),
            Map.entry(55, TransportType.BUS),
            Map.entry(56, TransportType.BUS),
            Map.entry(57, TransportType.BUS),
            Map.entry(58, TransportType.BUS),
            Map.entry(60, TransportType.BUS),
            Map.entry(61, TransportType.BUS),
            Map.entry(62, TransportType.BUS),
            Map.entry(81, TransportType.BUS),
            Map.entry(82, TransportType.BUS),
            Map.entry(83, TransportType.BUS)
    );

    private final VehicleAggregationService aggregationService;

    private HttpClient httpClient;
    private WebSocket webSocket;
    private WebSocket webSocket2;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final List<byte[]> binaryBuffer = new ArrayList<>();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PostConstruct
    public void connect() {
        httpClient = HttpClient.newHttpClient();
        doConnect();
    }

    @PreDestroy
    public void disconnect() {
        scheduler.shutdownNow();
        if (webSocket != null && !webSocket.isOutputClosed()) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }
        if (webSocket2 != null && !webSocket2.isOutputClosed()) {
            webSocket2.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }
        httpClient = null;
        log.info("EasyWayCollector: disconnected");
    }

    private void doConnect() {
        log.info("EasyWayCollector: connecting...");
        connectOne();//.thenRun(this::connectTwo);
    }

    private CompletableFuture<Void> connectOne() {
        return httpClient.newWebSocketBuilder()
                .header("Origin", "https://www.eway.in.ua")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36")
                .buildAsync(URI.create(WS_URL), this)
                .thenAccept(ws -> {
                    this.webSocket = ws;
                    log.info("EasyWayCollector: connection 1 established");
                })
                .exceptionally(e -> {
                    log.warn("EasyWayCollector: connection 1 failed — {}", e.getMessage());
                    scheduleReconnect();
                    return null;
                });
    }

    private void connectTwo() {
        httpClient.newWebSocketBuilder()
                .header("Origin", "https://www.eway.in.ua")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36")
                .buildAsync(URI.create(WS_URL), new WebSocket.Listener() {
                    private final StringBuilder buf = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket ws) {
                        log.info("EasyWayCollector: connection 2 established");
                        ws.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        buf.append(data);
                        if (last) {
                            String b64 = buf.toString();
                            buf.setLength(0);
                            try {
                                byte[] decrypted = aesDecrypt(Base64.getDecoder().decode(b64));
                                processMessage(decrypted);
                            } catch (Exception e) {
                                log.warn("EasyWayCollector ws2: decrypt failed — {}", e.getMessage());
                            }
                        }
                        ws.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                        log.warn("EasyWayCollector ws2: closed, reconnecting...");
                        scheduleReconnect();
                        return null;
                    }

                    @Override
                    public void onError(WebSocket ws, Throwable error) {
                        log.warn("EasyWayCollector ws2: error — {}", error.getMessage());
                        scheduleReconnect();
                    }
                })
                .thenAccept(ws -> webSocket2 = ws)
                .exceptionally(e -> {
                    log.warn("EasyWayCollector: connection 2 failed — {}", e.getMessage());
                    return null;
                });
    }


    private void scheduleReconnect() {
        if (!scheduler.isShutdown()) {
            scheduler.schedule(this::doConnect, RECONNECT_DELAY_SEC, TimeUnit.SECONDS);
        }
    }

    // ── WebSocket.Listener ────────────────────────────────────────────────────

    @Override
    public void onOpen(WebSocket ws) {
        log.info("EasyWayCollector: WebSocket opened");
        ws.request(1);
    }

    /**
     * Текстові фрейми — основний канал даних EasyWay.
     * Формат: base64(AES-CBC(protobuf))
     */
    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        textBuffer.append(data);
        if (last) {
            String base64 = textBuffer.toString();
            textBuffer.setLength(0);
            try {
                byte[] encrypted  = Base64.getDecoder().decode(base64);
                byte[] protobuf   = aesDecrypt(encrypted);
                log.debug("EasyWayCollector: decrypted {} → {} bytes", encrypted.length, protobuf.length);
                processMessage(protobuf);
            } catch (Exception e) {
                log.warn("EasyWayCollector: failed to decrypt text message — {}", e.getMessage());
            }
        }
        ws.request(1);
        return null;
    }

    /**
     * Бінарні фрейми — на випадок якщо сервер перейде на бінарний формат.
     * Спробуємо і AES і gzip.
     */
    @Override
    public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
        byte[] chunk = new byte[data.remaining()];
        data.get(chunk);
        binaryBuffer.add(chunk);

        if (last) {
            byte[] full = mergeChunks(binaryBuffer);
            binaryBuffer.clear();
            try {
                // Спочатку пробуємо AES (якщо виглядає як зашифроване)
                byte[] decrypted = tryDecrypt(full);
                processMessage(decrypted);
            } catch (Exception e) {
                log.warn("EasyWayCollector: binary message failed — {}", e.getMessage());
            }
        }
        ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
        log.warn("EasyWayCollector: closed (code={}, reason={}), reconnecting...", statusCode, reason);
        scheduleReconnect();
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        log.warn("EasyWayCollector: error — {}, reconnecting...", error.getMessage());
        scheduleReconnect();
    }

    // ── AES розшифрування ─────────────────────────────────────────────────────

    /**
     * AES-128-CBC розшифрування.
     * Ключ і IV: "k38hdGen3ksqAe3m" (16 байт = AES-128).
     * Витягнуто з JS функції ddt на eway.in.ua:
     *   var b = asfqwxv.gsr.asx.ps("k38hdGen3ksqAe3m")  ← ключ
     *   var c = asfqwxv.gsr.asx.ps("k38hdGen3ksqAe3m")  ← IV (той самий рядок)
     *   return asfqwxv.hdfg.dt(a, b, {iv: c})           ← AES decrypt
     */
    private static byte[] aesDecrypt(byte[] encrypted) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(AES_KEY, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(AES_IV);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        return cipher.doFinal(encrypted);
    }

    /**
     * Пробує розшифрувати бінарні дані:
     * спочатку AES, якщо не вийшло — повертає як є (може вже protobuf).
     */
    private static byte[] tryDecrypt(byte[] data) {
        try {
            return aesDecrypt(data);
        } catch (Exception e) {
            log.debug("EasyWayCollector: AES failed on binary, trying raw — {}", e.getMessage());
            return data;
        }
    }

    // ── Protobuf декодування ──────────────────────────────────────────────────

    /**
     * Структура повідомлення EasyWay (reverse-engineered):
     *
     * Message {
     *   field 1 (varint)  = timestamp
     *   field 2 (message) = repeated RouteEntry {
     *     field 1 (varint)  = routeId
     *     field 2 (message) = repeated VehiclePosition {
     *       field 1  (varint) = statusCode (0=online, 1=stopped)
     *       field 5  (varint) = direction
     *       field 6  (varint) = vehicleId
     *       field 7  (string) = vehicleLabel "075(6351)" — маршрут(бортовий)
     *       field 8  (varint) = angle/bearing (0–360)
     *       field 12 (varint) = speed (км/год)
     *       field 15 (varint) = lat * 1_000_000  (підтверджено: 48275470 → 48.27547)
     *       field 16 (varint) = lng * 1_000_000  (підтверджено: 25929660 → 25.92966)
     *     }
     *   }
     * }
     */
    private void processMessage(byte[] data) {
        if (data.length < 2) return;
        try {
            List<VehiclePositionDto> positions = new ArrayList<>();
            ProtoReader reader = new ProtoReader(data);

            while (reader.hasMore()) {
                int tag        = reader.readVarint32();
                int fieldNum   = tag >>> 3;
                int wireType   = tag & 0x7;

                if (fieldNum == 1 && wireType == 0) {
                    long ts = reader.readVarint64();
                    log.debug("EasyWay timestamp: {}", ts);
                } else if (fieldNum == 2 && wireType == 2) {
                    byte[] routeEntry = reader.readBytes();
                    parseRouteEntry(routeEntry, positions);
                } else {
                    reader.skipField(wireType);
                }
            }

            log.debug("EasyWay: parsed {} vehicles from {} bytes", positions.size(), data.length);

            if (!positions.isEmpty()) {
                aggregationService.processPositions(positions);
            }

        } catch (Exception e) {
            log.warn("EasyWayCollector: protobuf parse failed ({} bytes) — {}", data.length, e.getMessage());
        }
    }

    private void parseRouteEntry(byte[] data, List<VehiclePositionDto> out) {
        ProtoReader reader = new ProtoReader(data);
        int routeId = -1;

        while (reader.hasMore()) {
            int tag      = reader.readVarint32();
            int fieldNum = tag >>> 3;
            int wireType = tag & 0x7;

            switch (fieldNum) {
                case 1 -> routeId = reader.readVarint32();
                case 2 -> {
                    byte[] vehicleBlock = reader.readBytes();
                    VehiclePositionDto dto = parseVehicle(vehicleBlock, routeId);
                    if (dto != null) out.add(dto);
                }
                default -> reader.skipField(wireType);
            }
        }
    }

    private VehiclePositionDto parseVehicle(byte[] data, int routeId) {
        ProtoReader reader = new ProtoReader(data);

        int    statusCode    = 0;
        int    vehicleId     = -1;
        String vehicleLabel  = null;  // "075(6351)" — маршрут(бортовий)
        int    angle         = 0;
        double lat           = 0;
        double lng           = 0;
        int    speed         = 0;

        while (reader.hasMore()) {
            int tag      = reader.readVarint32();
            int fieldNum = tag >>> 3;
            int wireType = tag & 0x7;

            switch (fieldNum) {
                case 1  -> statusCode   = reader.readVarint32();  // 0=online, 1=stopped
                case 2  -> reader.readVarint32();                  // handicapped
                case 3  -> reader.readVarint32();                  // wifi
                case 4  -> reader.readVarint32();                  // aircond
                case 5  -> reader.readVarint32();                  // direction
                case 6  -> vehicleId    = reader.readVarint32();
                case 7  -> vehicleLabel = reader.readString();     // "075(6351)"
                case 8  -> angle        = reader.readVarint32();
                case 9  -> reader.readVarint32();                  // tripId
                case 10 -> reader.readVarint32();                  // index
                case 11 -> reader.readVarint64();                  // timestamp
                case 12 -> speed        = reader.readVarint32();
                case 13 -> reader.readVarint32();                  // speedRaw
                case 14 -> reader.readVarint32();                  // speedAi
                // Координати як varint * 1_000_000 (підтверджено реверс-інжинірингом)
                case 15 -> lat          = reader.readVarint64() / 1_000_000.0;
                case 16 -> lng          = reader.readVarint64() / 1_000_000.0;
                default -> reader.skipField(wireType);
            }
        }

        log.info("EasyWay vehicle={} statusCode={} lat={} lng={}", vehicleId, statusCode, lat, lng);

        if (vehicleId < 0 || lat == 0 || lng == 0) return null;
        if (lat < LAT_MIN || lat > LAT_MAX) return null;
        if (lng < LNG_MIN || lng > LNG_MAX) return null;

        // Витягуємо бортовий номер з vehicleLabel "075(6351)" → "6351"
        String busNumber = extractBusNumber(vehicleLabel);

        String routeName = ROUTE_NAMES.get(routeId);
        if (routeName == null) {
            //log.debug("EasyWayCollector: unknown routeId={}", routeId);
            return null;
        }

        TransportType type = ROUTE_TYPES.getOrDefault(routeId, TransportType.BUS);

        return VehiclePositionDto.builder()
                .externalId(String.valueOf(vehicleId))
                .source(DataSource.easyway)
                .externalRouteId(String.valueOf(routeId))
                .routeName(routeName)
                .type(type)
                .lat(lat)
                .lng(lng)
                .speed((float) speed)
                .bearing((float) angle)
                .busNumber(busNumber)
                .online(true)
                .build();
    }

    /**
     * Витягує бортовий номер з рядка формату "075(6351)".
     * Якщо формат інший — повертає весь рядок.
     */
    private static String extractBusNumber(String label) {
        if (label == null) return null;
        int start = label.indexOf('(');
        int end   = label.indexOf(')');
        if (start >= 0 && end > start) {
            return label.substring(start + 1, end);
        }
        return label;
    }

    // ── Утиліти ───────────────────────────────────────────────────────────────

    private static byte[] mergeChunks(List<byte[]> chunks) {
        int total = chunks.stream().mapToInt(c -> c.length).sum();
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        return result;
    }

    // ── Мінімальний Protobuf reader ───────────────────────────────────────────

    private static class ProtoReader {
        private final byte[] buf;
        private int pos;

        ProtoReader(byte[] buf) { this.buf = buf; this.pos = 0; }

        boolean hasMore() { return pos < buf.length; }

        int readVarint32() {
            int result = 0, shift = 0;
            while (pos < buf.length) {
                int b = buf[pos++] & 0xFF;
                result |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            return result;
        }

        long readVarint64() {
            long result = 0;
            int shift = 0;
            while (pos < buf.length) {
                long b = buf[pos++] & 0xFF;
                result |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            return result;
        }

        byte[] readBytes() {
            int len = readVarint32();
            byte[] data = new byte[len];
            System.arraycopy(buf, pos, data, 0, len);
            pos += len;
            return data;
        }

        String readString() {
            return new String(readBytes(), StandardCharsets.UTF_8);
        }

        void skipField(int wireType) {
            switch (wireType) {
                case 0 -> readVarint64();
                case 1 -> pos += 8;
                case 2 -> { int len = readVarint32(); pos += len; }
                case 5 -> pos += 4;
                default -> pos = buf.length;
            }
        }
    }
}