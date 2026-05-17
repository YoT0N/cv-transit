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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Підключається до WebSocket EasyWay і отримує позиції транспорту Чернівців.
 *
 * Протокол: WSS (WebSocket Secure)
 * URL: wss://ws.easyway.info/sub/gps/{token}
 * Формат: Google Protocol Buffers (бінарний)
 *
 * Маппінг routeId → назва і тип отримано з window._cityRoutes на eway.in.ua.
 * Токен: 52822d67a2fb3376bbed239d7c0ca7ae (публічний, для Чернівців)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "easyway.collector.enabled", havingValue = "true", matchIfMissing = false)
public class EasyWayCollector implements WebSocket.Listener {

    private static final String WS_URL =
            "wss://ws.easyway.info/sub/gps/52822d67a2fb3376bbed239d7c0ca7ae";

    // Інтервал reconnect при розриві з'єднання
    private static final long RECONNECT_DELAY_SEC = 10;

    // Координатні межі Чернівців (для валідації)
    private static final double LAT_MIN = 47.5, LAT_MAX = 49.0;
    private static final double LNG_MIN = 24.5, LNG_MAX = 27.0;
    private final StringBuilder textBuffer = new StringBuilder();

    /**
     * Маппінг EasyWay routeId → назва маршруту.
     * Отримано з window._cityRoutes.routes на eway.in.ua/ua/cities/chernivtsi
     * Формат: ri (routeId) → rn (routeName)
     */
    private static final Map<Integer, String> ROUTE_NAMES = Map.ofEntries(
            Map.entry(1,  "1"),   // trol
            Map.entry(2,  "2"),   // trol
            Map.entry(3,  "4"),   // trol
            Map.entry(4,  "3"),   // trol
            Map.entry(6,  "6A"),  // trol
            Map.entry(7,  "6"),   // trol
            Map.entry(8,  "5"),   // trol
            Map.entry(9,  "3"),   // bus
            Map.entry(10, "4"),   // bus
            Map.entry(11, "6"),   // bus
            Map.entry(14, "26"),  // bus
            Map.entry(15, "29"),  // bus
            Map.entry(16, "30"),  // bus
            Map.entry(18, "31"),  // bus
            Map.entry(20, "5"),   // bus
            Map.entry(21, "8"),   // bus
            Map.entry(22, "9A"),  // bus
            Map.entry(23, "10A"), // bus
            Map.entry(25, "13"),  // bus
            Map.entry(26, "15"),  // bus
            Map.entry(27, "15К"), // bus
            Map.entry(30, "19"),  // bus
            Map.entry(32, "21"),  // bus
            Map.entry(34, "23"),  // bus
            Map.entry(36, "27"),  // bus
            Map.entry(38, "36"),  // bus
            Map.entry(39, "37"),  // bus
            Map.entry(40, "32"),  // bus
            Map.entry(41, "34"),  // bus
            Map.entry(44, "39"),  // bus
            Map.entry(45, "1"),   // bus
            Map.entry(48, "41"),  // bus
            Map.entry(52, "43"),  // bus
            Map.entry(53, "33"),  // bus
            Map.entry(55, "7"),   // bus
            Map.entry(56, "14"),  // bus
            Map.entry(57, "25"),  // bus
            Map.entry(58, "24"),  // bus
            Map.entry(60, "10"),  // bus
            Map.entry(61, "20"),  // bus
            Map.entry(62, "35"),  // bus
            Map.entry(75, "8"),   // trol
            Map.entry(81, "8А"),  // bus
            Map.entry(82, "9"),   // bus
            Map.entry(83, "35A")  // bus
    );

    /**
     * Маппінг EasyWay routeId → тип транспорту.
     * tk:"trol" → TROLL, tk:"bus" → BUS
     */
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
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // Буфер для збору фрагментованих бінарних повідомлень
    private final List<byte[]> messageBuffer = new ArrayList<>();

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
        // HttpClient в Java 17 не має close() — просто nullify
        httpClient = null;
        log.info("EasyWayCollector: disconnected");
    }

    private void doConnect() {
        log.info("EasyWayCollector: connecting to {}...", WS_URL);
        httpClient.newWebSocketBuilder()
                .header("Origin", "https://www.eway.in.ua")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36")
                .buildAsync(URI.create(WS_URL), this)
                .thenAccept(ws -> {
                    this.webSocket = ws;
                    log.info("EasyWayCollector: connected successfully");
                })
                .exceptionally(e -> {
                    log.warn("EasyWayCollector: connection failed — {}, retry in {}s",
                            e.getMessage(), RECONNECT_DELAY_SEC);
                    scheduleReconnect();
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
    public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer data, boolean last) {
        // Збираємо фрагменти повідомлення
        byte[] chunk = new byte[data.remaining()];
        data.get(chunk);
        messageBuffer.add(chunk);

        if (last) {
            byte[] fullMessage = mergeChunks(messageBuffer);
            messageBuffer.clear();
            try {
                byte[] decompressed = decompress(fullMessage);
                processMessage(decompressed);
            } catch (Exception e) {
                log.warn("EasyWayCollector: decompress failed — {}", e.getMessage());
            }
        }

        ws.request(1); // дозволяємо наступне повідомлення
        return null;
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        textBuffer.append(data);
        if (last) {
            String fullText = textBuffer.toString();
            textBuffer.setLength(0);
            try {
                byte[] decoded = java.util.Base64.getDecoder().decode(fullText);
                byte[] decompressed = decompress(decoded);
                log.debug("EasyWayCollector: decoded {} bytes → decompressed {} bytes",
                        decoded.length, decompressed.length);
                processMessage(decompressed);
            } catch (Exception e) {
                log.warn("EasyWayCollector: failed to decode/decompress — {}", e.getMessage());
            }
        }
        ws.request(1);
        return null;
    }

    private static byte[] decompress(byte[] data) throws java.io.IOException {
        // Перевіряємо magic bytes:
        // gzip:  0x1F 0x8B
        // zlib:  0x78 0x9C / 0x78 0x01 / 0x78 0xDA
        // якщо не стиснене — повертаємо як є
        if (data.length < 2) return data;

        int b0 = data[0] & 0xFF;
        int b1 = data[1] & 0xFF;

        if (b0 == 0x1F && b1 == 0x8B) {
            // gzip
            try (var in = new java.util.zip.GZIPInputStream(
                    new java.io.ByteArrayInputStream(data));
                 var out = new java.io.ByteArrayOutputStream()) {
                in.transferTo(out);
                return out.toByteArray();
            }
        } else if (b0 == 0x78) {
            // zlib (deflate with header)
            try (var in = new java.util.zip.InflaterInputStream(
                    new java.io.ByteArrayInputStream(data));
                 var out = new java.io.ByteArrayOutputStream()) {
                in.transferTo(out);
                return out.toByteArray();
            }
        }

        return data; // не стиснене
    }


    @Override
    public void onOpen(WebSocket ws) {
        log.info("EasyWayCollector: WebSocket opened");
        ws.request(1);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
        log.warn("EasyWayCollector: connection closed (code={}, reason={}), reconnecting...",
                statusCode, reason);
        scheduleReconnect();
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        log.warn("EasyWayCollector: WebSocket error — {}, reconnecting...", error.getMessage());
        scheduleReconnect();
    }

    // ── Protobuf декодування ──────────────────────────────────────────────────

    /**
     * Декодує бінарне повідомлення Protocol Buffers вручну (без .proto файлу).
     *
     * Структура повідомлення EasyWay GPS (reverse-engineered):
     * Зовнішнє повідомлення — список записів про транспорт.
     * Кожен запис містить поля:
     *   field 1 (varint)  → routeId
     *   field 2 (message) → вкладений список vehicles на маршруті
     *     field 1 (varint)  → vehicleId
     *     field 2 (string)  → vehicleNumber
     *     field 3 (varint)  → angle (bearing, 0-360)
     *     field 4 (fixed32) → lat * 1_000_000 або float
     *     field 5 (fixed32) → lng * 1_000_000 або float
     *     field 6 (varint)  → speed
     *     field 7 (varint)  → status (0=OK, 1=STOP)
     *
     * УВАГА: якщо структура відрізняється — дивись логи "unknown field" і коригуй.
     */
    private void processMessage(byte[] data) {
        try {
            List<VehiclePositionDto> positions = new ArrayList<>();
            ProtoReader reader = new ProtoReader(data);
            int fieldCount = 0;

            while (reader.hasMore()) {
                int tag = reader.readVarint32();
                int fieldNumber = tag >>> 3;
                int wireType   = tag & 0x7;
                fieldCount++;

                if (fieldNumber == 1 && wireType == 0) {
                    long timestamp = reader.readVarint64();
                    log.info("EasyWay: timestamp={}, data[0]={}, data[1]={}, data[2]={}",
                            timestamp, data[0] & 0xFF, data[1] & 0xFF, data[2] & 0xFF);
                } else if (fieldNumber == 2 && wireType == 2) {
                    byte[] mapEntry = reader.readBytes();
                    parseMapEntry(mapEntry, positions);
                } else {
                    log.info("EasyWay: unexpected field={} wireType={} at start", fieldNumber, wireType);
                    reader.skipField(wireType);
                }
            }

            log.info("EasyWay: fieldCount={}, positions={}, dataLen={}, first3bytes={},{},{}",
                    fieldCount, positions.size(), data.length,
                    data[0]&0xFF, data[1]&0xFF, data[2]&0xFF);

            if (!positions.isEmpty()) {
                aggregationService.processPositions(positions);
            }

        } catch (Exception e) {
            log.warn("EasyWayCollector: failed ({} bytes) — {}, first3bytes={},{},{}",
                    data.length, e.getMessage(),
                    data[0]&0xFF, data[1]&0xFF, data[2]&0xFF);
        }
    }


    private void parseMapEntry(byte[] data, List<VehiclePositionDto> out) {
        // Map entry: field 1 = routeId (key), field 2 = RoutePositions (value)
        ProtoReader reader = new ProtoReader(data);
        int routeId = -1;

        while (reader.hasMore()) {
            int tag = reader.readVarint32();
            int fieldNumber = tag >>> 3;
            int wireType   = tag & 0x7;

            switch (fieldNumber) {
                case 1 -> routeId = reader.readVarint32(); // key = routeId
                case 2 -> {
                    // value = RoutePositions message
                    byte[] routePositions = reader.readBytes();
                    parseRoutePositions(routePositions, routeId, out);
                }
                default -> reader.skipField(wireType);
            }
        }
    }

    private void parseRoutePositions(byte[] data, int routeId, List<VehiclePositionDto> out) {
        // RoutePositions: repeated field 1 = VehiclePosition (positionsList)
        ProtoReader reader = new ProtoReader(data);

        while (reader.hasMore()) {
            int tag = reader.readVarint32();
            int fieldNumber = tag >>> 3;
            int wireType   = tag & 0x7;

            if (fieldNumber == 1 && wireType == 2) {
                byte[] vehicleBlock = reader.readBytes();
                VehiclePositionDto dto = parseVehicle(vehicleBlock, routeId);
                if (dto != null) out.add(dto);
            } else {
                reader.skipField(wireType);
            }
        }
    }


    private VehiclePositionDto parseVehicle(byte[] data, int routeId) {
        ProtoReader reader = new ProtoReader(data);

        int    statusCode    = 0;
        int    vehicleId     = -1;
        String vehicleNumber = null;
        int    angle         = 0;
        double lat           = 0;
        double lng           = 0;
        int    speed         = 0;

        while (reader.hasMore()) {
            int tag = reader.readVarint32();
            int fieldNumber = tag >>> 3;
            int wireType   = tag & 0x7;

            switch (fieldNumber) {
                case 1  -> statusCode    = reader.readVarint32();  // status: 0=OK, 1=STOP
                case 2  -> reader.readVarint32();                  // handicapped (bool)
                case 3  -> reader.readVarint32();                  // wifi (bool)
                case 4  -> reader.readVarint32();                  // aircond (bool)
                case 5  -> reader.readVarint32();                  // direction
                case 6  -> vehicleId     = reader.readVarint32();  // vehicleId
                case 7  -> vehicleNumber = reader.readString();    // vehicleNumber
                case 8  -> angle         = reader.readVarint32();  // angle
                case 9  -> reader.readVarint32();                  // tripId
                case 10 -> reader.readVarint32();                  // index
                case 11 -> reader.readVarint64();                  // timestamp
                case 12 -> speed         = reader.readVarint32();  // speed
                case 13 -> reader.readVarint32();                  // speedRaw
                case 14 -> reader.readVarint32();                  // speedAi
                case 15 -> lat           = reader.readFloat();     // lat (float32)
                case 16 -> lng           = reader.readFloat();     // lng (float32)
                default -> reader.skipField(wireType);
            }
        }

        if (vehicleId < 0 || lat == 0 || lng == 0) return null;
        if (lat < LAT_MIN || lat > LAT_MAX) return null;
        if (lng < LNG_MIN || lng > LNG_MAX) return null;

        String routeName = ROUTE_NAMES.get(routeId);
        TransportType type = ROUTE_TYPES.getOrDefault(routeId, TransportType.BUS);

        if (routeName == null) {
            log.debug("EasyWayCollector: unknown routeId={}", routeId);
            return null;
        }

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
                .busNumber(vehicleNumber)
                .online(statusCode == 0)
                .build();
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

    /**
     * Мінімальний декодер Protocol Buffers без зовнішніх залежностей.
     * Підтримує wire types: 0 (varint), 1 (64-bit), 2 (length-delimited), 5 (32-bit).
     */
    private static class ProtoReader {
        private final byte[] buf;
        private int pos;

        ProtoReader(byte[] buf) {
            this.buf = buf;
            this.pos = 0;
        }

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
            return new String(readBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        float readFloat() {
            int bits = (buf[pos] & 0xFF)
                    | ((buf[pos + 1] & 0xFF) << 8)
                    | ((buf[pos + 2] & 0xFF) << 16)
                    | ((buf[pos + 3] & 0xFF) << 24);
            pos += 4;
            return Float.intBitsToFloat(bits);
        }

        void skipField(int wireType) {
            switch (wireType) {
                case 0 -> readVarint64();
                case 1 -> pos += 8;
                case 2 -> { int len = readVarint32(); pos += len; }
                case 5 -> pos += 4;
                default -> pos = buf.length; // невідомий wire type — пропускаємо пакет
            }
        }
    }
}