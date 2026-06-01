package edu.ilkiv.transit.collector;

import edu.ilkiv.transit.dto.VehiclePositionDto;
import edu.ilkiv.transit.service.VehicleAggregationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Field;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static edu.ilkiv.transit.collector.EasyWayCollectorTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Додаткові тести для підвищення branch coverage у EasyWayCollector.
 *
 * Що покривають ці тести:
 * 1. onBinary — фрагментовані фрейми + валідний AES payload
 * 2. mergeChunks — кілька фрагментів
 * 3. disconnect — коли webSocket2 != null
 * 4. parseVehicle — гілки з усіма optional полями (statusCode, speed, angle, etc.)
 * 5. processMessage — гілка "відсутній vehicleLabel (null busNumber)"
 * 6. onText — невалідний base64 (не тільки невалідний AES)
 * 7. ProtoReader — skipField для wireType 1 (64-bit), 5 (32-bit), default (невідомий)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EasyWayCollector — branch coverage tests")
class EasyWayCollectorBranchTest {

    private static final byte[] AES_KEY = "k38hdGen3ksqAe3m".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AES_IV  = "k38hdGen3ksqAe3m".getBytes(StandardCharsets.UTF_8);

    @Mock
    VehicleAggregationService aggregationService;

    EasyWayCollector collector;

    @BeforeEach
    void setUp() {
        collector = new EasyWayCollector(aggregationService);
    }

    // ── onBinary — гілки ────────────────────────────────────────────────────

    @Nested
    @DisplayName("onBinary — branch coverage")
    class OnBinaryBranchTests {

        /**
         * Гілка: last=true, дані є валідним AES → розшифровується і обробляється.
         */
        @Test
        @DisplayName("onBinary — валідний AES payload — vehicle агрегується")
        void onBinary_validAesPayload_aggregated() throws Exception {
            WebSocket ws = mock(WebSocket.class);

            byte[] protobuf  = buildProtobufWithVehicle(10, 1, 48_275_470L, 25_929_660L, "001(1111)");
            byte[] encrypted = aesEncrypt(protobuf);

            ByteBuffer buf = ByteBuffer.wrap(encrypted);
            collector.onBinary(ws, buf, true);

            verify(aggregationService).processPositions(any());
            verify(ws).request(1);
        }

        /**
         * Гілка: last=false → дані буферизуються, processMessage НЕ викликається.
         */
        @Test
        @DisplayName("onBinary — last=false — дані буферизуються без обробки")
        void onBinary_notLast_buffered() throws Exception {
            WebSocket ws = mock(WebSocket.class);

            byte[] chunk = new byte[]{0x01, 0x02, 0x03};
            collector.onBinary(ws, ByteBuffer.wrap(chunk), false);

            verifyNoInteractions(aggregationService);
            verify(ws).request(1);
        }

        /**
         * Гілка: два фрагменти (last=false + last=true) → mergeChunks збирає разом.
         */
        @Test
        @DisplayName("onBinary — два фрагменти (last=false, last=true) — збираються разом")
        void onBinary_twoFragments_merged() throws Exception {
            WebSocket ws = mock(WebSocket.class);

            byte[] protobuf  = buildProtobufWithVehicle(20, 1, 48_275_470L, 25_929_660L, "002(2222)");
            byte[] encrypted = aesEncrypt(protobuf);

            // Ділимо на два шматки
            int half = encrypted.length / 2;
            byte[] part1 = new byte[half];
            byte[] part2 = new byte[encrypted.length - half];
            System.arraycopy(encrypted, 0,    part1, 0, half);
            System.arraycopy(encrypted, half, part2, 0, part2.length);

            collector.onBinary(ws, ByteBuffer.wrap(part1), false);
            verifyNoInteractions(aggregationService);

            collector.onBinary(ws, ByteBuffer.wrap(part2), true);
            verify(aggregationService).processPositions(any());
        }

        /**
         * Гілка: tryDecrypt — AES невдалий, повертає сирі байти.
         * Якщо сирі байти не є валідним protobuf — нічого не передається.
         */
        @Test
        @DisplayName("onBinary — non-AES bytes — tryDecrypt повертає оригінал, protobuf parse fails gracefully")
        void onBinary_nonAesBytes_noException() throws Exception {
            WebSocket ws = mock(WebSocket.class);

            // Довгі невалідні байти (AES вимагає кратності 16)
            byte[] garbage = new byte[32];
            for (int i = 0; i < garbage.length; i++) garbage[i] = (byte) i;

            assertThatCode(() -> collector.onBinary(ws, ByteBuffer.wrap(garbage), true))
                    .doesNotThrowAnyException();
        }
    }

    // ── parseVehicle — optional fields branch coverage ───────────────────────

    @Nested
    @DisplayName("parseVehicle — optional fields")
    class ParseVehicleOptionalFieldsTests {

        /**
         * Покриваємо поля 1 (statusCode), 2 (handicapped), 3 (wifi),
         * 4 (aircond), 5 (direction), 9 (tripId), 10 (index),
         * 11 (timestamp), 13 (speedRaw), 14 (speedAi).
         * Всі вони є varint полями що просто читаються і ігноруються —
         * але їх гілки у switch мають бути покриті.
         */
        @Test
        @DisplayName("parseVehicle — всі optional varint поля не кидають exception")
        void parseVehicle_allOptionalVarintFields_noException() throws Exception {
            byte[] vehicleBytes = concat(
                    fieldVarint(1,  1L),   // statusCode = stopped
                    fieldVarint(2,  0L),   // handicapped
                    fieldVarint(3,  1L),   // wifi
                    fieldVarint(4,  1L),   // aircond
                    fieldVarint(5,  1L),   // direction
                    fieldVarint(6,  77L),  // vehicleId
                    fieldLenDelim(7, label("077(7777)")),
                    fieldVarint(8,  45L),  // angle
                    fieldVarint(9,  100L), // tripId
                    fieldVarint(10, 3L),   // index
                    fieldVarint(11, System.currentTimeMillis() / 1000), // timestamp
                    fieldVarint(12, 20L),  // speed
                    fieldVarint(13, 20L),  // speedRaw
                    fieldVarint(14, 18L),  // speedAi
                    fieldVarint(15, 48_275_470L),
                    fieldVarint(16, 25_929_660L)
            );
            byte[] routePositions = fieldLenDelim(1, vehicleBytes);
            byte[] routeEntry     = concat(fieldVarint(1, 1L), fieldLenDelim(2, routePositions));
            byte[] msg            = concat(
                    fieldVarint(1, System.currentTimeMillis() / 1000),
                    fieldLenDelim(2, routeEntry)
            );

            invokeProcessMessage(collector, msg);

            ArgumentCaptor<List<VehiclePositionDto>> captor = ArgumentCaptor.forClass(List.class);
            verify(aggregationService).processPositions(captor.capture());
            assertThat(captor.getValue()).hasSize(1);
            // Bearing і speed мають передаватись коректно
            assertThat(captor.getValue().get(0).getSpeed()).isEqualTo(20f);
            assertThat(captor.getValue().get(0).getBearing()).isEqualTo(45f);
        }

        /**
         * Гілка: vehicleLabel = null → busNumber = null (extractBusNumber отримує null).
         */
        @Test
        @DisplayName("parseVehicle — без поля 7 (label) → busNumber = null")
        void parseVehicle_noLabel_busNumberNull() throws Exception {
            // VehicleBlock без поля 7 (vehicleLabel)
            byte[] vehicleBytes = concat(
                    fieldVarint(6,  55L),
                    fieldVarint(8,  0L),
                    fieldVarint(12, 0L),
                    fieldVarint(15, 48_275_470L),
                    fieldVarint(16, 25_929_660L)
            );
            byte[] routePositions = fieldLenDelim(1, vehicleBytes);
            byte[] routeEntry     = concat(fieldVarint(1, 1L), fieldLenDelim(2, routePositions));
            byte[] msg            = concat(
                    fieldVarint(1, System.currentTimeMillis() / 1000),
                    fieldLenDelim(2, routeEntry)
            );

            invokeProcessMessage(collector, msg);

            ArgumentCaptor<List<VehiclePositionDto>> captor = ArgumentCaptor.forClass(List.class);
            verify(aggregationService).processPositions(captor.capture());
            assertThat(captor.getValue().get(0).getBusNumber()).isNull();
        }

        /**
         * Гілка: label без дужок → busNumber = label як є.
         */
        @Test
        @DisplayName("parseVehicle — label без дужок → busNumber = label")
        void parseVehicle_labelWithoutParens_busNumberIsLabel() throws Exception {
            byte[] vehicleBytes = concat(
                    fieldVarint(6,  66L),
                    fieldLenDelim(7, label("12345")),  // без дужок
                    fieldVarint(15, 48_275_470L),
                    fieldVarint(16, 25_929_660L)
            );
            byte[] routePositions = fieldLenDelim(1, vehicleBytes);
            byte[] routeEntry     = concat(fieldVarint(1, 1L), fieldLenDelim(2, routePositions));
            byte[] msg            = concat(
                    fieldVarint(1, System.currentTimeMillis() / 1000),
                    fieldLenDelim(2, routeEntry)
            );

            invokeProcessMessage(collector, msg);

            ArgumentCaptor<List<VehiclePositionDto>> captor = ArgumentCaptor.forClass(List.class);
            verify(aggregationService).processPositions(captor.capture());
            assertThat(captor.getValue().get(0).getBusNumber()).isEqualTo("12345");
        }
    }

    // ── ProtoReader — skipField wireType branches ─────────────────────────────

    @Nested
    @DisplayName("ProtoReader — skipField wireType гілки")
    class ProtoReaderSkipFieldTests {

        /**
         * wireType=1 (64-bit/fixed64) — skip 8 байт.
         * Для цього у protobuf повідомленні додаємо невідоме поле з wireType=1.
         */
        @Test
        @DisplayName("processMessage — невідоме поле wireType=1 (64-bit) — пропускається")
        void processMessage_unknownField_wireType1_skipped() throws Exception {
            // Будуємо повідомлення де top-level поле 3 має wireType=1
            byte[] unknownFixed64 = concat(
                    varint((3L << 3) | 1),  // fieldNum=3, wireType=1
                    new byte[]{1, 2, 3, 4, 5, 6, 7, 8}  // 8 bytes fixed64
            );
            byte[] vehicle  = buildVehicleBlock(10, 48_275_470L, 25_929_660L, "001(1001)");
            byte[] rPos     = fieldLenDelim(1, vehicle);
            byte[] rEntry   = concat(fieldVarint(1, 1L), fieldLenDelim(2, rPos));
            byte[] msg      = concat(
                    fieldVarint(1, System.currentTimeMillis() / 1000),
                    unknownFixed64,
                    fieldLenDelim(2, rEntry)
            );

            assertThatCode(() -> invokeProcessMessage(collector, msg)).doesNotThrowAnyException();
            verify(aggregationService).processPositions(any());
        }

        /**
         * wireType=5 (32-bit/fixed32) — skip 4 байт.
         */
        @Test
        @DisplayName("processMessage — невідоме поле wireType=5 (32-bit) — пропускається")
        void processMessage_unknownField_wireType5_skipped() throws Exception {
            byte[] unknownFixed32 = concat(
                    varint((3L << 3) | 5),  // fieldNum=3, wireType=5
                    new byte[]{1, 2, 3, 4}  // 4 bytes fixed32
            );
            byte[] vehicle  = buildVehicleBlock(11, 48_275_470L, 25_929_660L, "001(1111)");
            byte[] rPos     = fieldLenDelim(1, vehicle);
            byte[] rEntry   = concat(fieldVarint(1, 1L), fieldLenDelim(2, rPos));
            byte[] msg      = concat(
                    fieldVarint(1, System.currentTimeMillis() / 1000),
                    unknownFixed32,
                    fieldLenDelim(2, rEntry)
            );

            assertThatCode(() -> invokeProcessMessage(collector, msg)).doesNotThrowAnyException();
            verify(aggregationService).processPositions(any());
        }

        /**
         * wireType=3 — невідомий тип → ProtoReader встановлює pos = buf.length (abort).
         */
        @Test
        @DisplayName("processMessage — невідомий wireType=3 — парсинг переривається gracefully")
        void processMessage_unknownWireType3_gracefulAbort() throws Exception {
            byte[] badField = varint((3L << 3) | 3); // wireType=3 невалідний
            byte[] msg = concat(fieldVarint(1, 0L), badField);

            assertThatCode(() -> invokeProcessMessage(collector, msg)).doesNotThrowAnyException();
        }
    }

    // ── disconnect — webSocket2 branch ───────────────────────────────────────

    @Nested
    @DisplayName("disconnect — webSocket2 гілки")
    class DisconnectWebSocket2Tests {

        /**
         * Гілка: webSocket2 != null і not closed → sendClose викликається.
         */
        @Test
        @DisplayName("disconnect — webSocket2 не null і відкритий → sendClose викликається")
        void disconnect_webSocket2Open_sendCloseCalled() throws Exception {
            WebSocket ws2 = mock(WebSocket.class);
            when(ws2.isOutputClosed()).thenReturn(false);

            setPrivateField(collector, "webSocket2", ws2);

            collector.disconnect();

            verify(ws2).sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }

        /**
         * Гілка: webSocket2 != null але вже закритий → sendClose НЕ викликається.
         */
        @Test
        @DisplayName("disconnect — webSocket2 вже закритий → sendClose не викликається")
        void disconnect_webSocket2AlreadyClosed_noSendClose() throws Exception {
            WebSocket ws2 = mock(WebSocket.class);
            when(ws2.isOutputClosed()).thenReturn(true);

            setPrivateField(collector, "webSocket2", ws2);

            collector.disconnect();

            verify(ws2, never()).sendClose(anyInt(), anyString());
        }

        /**
         * Гілка: webSocket != null і відкритий → sendClose для ws1 теж викликається.
         */
        @Test
        @DisplayName("disconnect — webSocket1 відкритий → sendClose викликається")
        void disconnect_webSocket1Open_sendCloseCalled() throws Exception {
            WebSocket ws1 = mock(WebSocket.class);
            when(ws1.isOutputClosed()).thenReturn(false);

            setPrivateField(collector, "webSocket", ws1);

            collector.disconnect();

            verify(ws1).sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }
    }

    // ── onText — edge cases ─────────────────────────────────────────────────

    @Nested
    @DisplayName("onText — edge cases")
    class OnTextEdgeCases {

        /**
         * Гілка: last=false → textBuffer накопичується, aggregationService не викликається.
         */
        @Test
        @DisplayName("onText — last=false → нічого не обробляється")
        void onText_notLast_noProcessing() {
            WebSocket ws = mock(WebSocket.class);
            collector.onText(ws, "SGVsbG8=", false);

            verifyNoInteractions(aggregationService);
            verify(ws).request(1);
        }

        /**
         * Гілка: валідний base64 але AES decrypt кидає exception
         * (дані не кратні 16 байтам після decode).
         */
        @Test
        @DisplayName("onText — валідний base64 але не AES → warn логується, без exception")
        void onText_validBase64ButNotAes_noException() {
            WebSocket ws = mock(WebSocket.class);
            // "AQID" = base64 для [0x01, 0x02, 0x03] — 3 байти, не кратно 16
            assertThatCode(() -> collector.onText(ws, "AQID", true))
                    .doesNotThrowAnyException();
            verifyNoInteractions(aggregationService);
        }

        /**
         * Гілка: textBuffer збирає два фрагменти і тільки після last=true обробляє.
         * Цей тест покриває гілку "last=true" після попереднього "last=false".
         */
        @Test
        @DisplayName("onText — 3 фрагменти → обробляється тільки після останнього")
        void onText_threeFragments_processedOnlyAtEnd() throws Exception {
            WebSocket ws = mock(WebSocket.class);

            byte[] msg       = buildProtobufWithVehicle(5, 1, 48_275_470L, 25_929_660L, "001(5555)");
            byte[] encrypted = aesEncrypt(msg);
            String b64       = Base64.getEncoder().encodeToString(encrypted);

            int third = b64.length() / 3;
            collector.onText(ws, b64.substring(0, third), false);
            collector.onText(ws, b64.substring(third, 2 * third), false);
            verifyNoInteractions(aggregationService);

            collector.onText(ws, b64.substring(2 * third), true);
            verify(aggregationService).processPositions(any());
        }
    }

    // ── TransGpsCollector — speed/bearing parsing branches ───────────────────
    // (Окремий nested клас щоб логічно відокремити)

    @Nested
    @DisplayName("TransGpsCollector — speed/bearing parsing edge cases")
    class TransGpsSpeedBearingTests {

        /**
         * Покриваємо гілку catch у parseFloat для speed:
         * TransGpsCollector ловить NumberFormatException і дефолтить до 0f.
         */
        @Test
        @DisplayName("TransGpsVehicleDto — speed='N/A' → parseFloat кидає exception → 0f")
        void speedParsing_invalidString_defaultsToZero() throws Exception {
            // Через reflection викликаємо toPositionDto
            edu.ilkiv.transit.collector.TransGpsCollector tgc =
                    new edu.ilkiv.transit.collector.TransGpsCollector(
                            mock(org.springframework.web.reactive.function.client.WebClient.class),
                            aggregationService
                    );

            edu.ilkiv.transit.dto.TransGpsVehicleDto dto = new edu.ilkiv.transit.dto.TransGpsVehicleDto();
            dto.setImei("test-imei");
            dto.setLat(48.29);
            dto.setLng(25.93);
            dto.setSpeed("N/A");          // невалідне число
            dto.setOrientation("abc");    // невалідне число
            dto.setRouteName("10");
            dto.setRouteId(10);
            dto.setOnline(true);

            java.lang.reflect.Method m = TransGpsCollector.class
                    .getDeclaredMethod("toPositionDto",
                            edu.ilkiv.transit.dto.TransGpsVehicleDto.class);
            m.setAccessible(true);
            edu.ilkiv.transit.dto.VehiclePositionDto result =
                    (edu.ilkiv.transit.dto.VehiclePositionDto) m.invoke(tgc, dto);

            assertThat(result.getSpeed()).isEqualTo(0f);
            assertThat(result.getBearing()).isEqualTo(0f);
        }

        /**
         * Гілка: speed і orientation є null → trim() кине NPE → catch → 0f.
         */
        @Test
        @DisplayName("TransGpsVehicleDto — speed=null → catch NPE → 0f")
        void speedParsing_null_defaultsToZero() throws Exception {
            edu.ilkiv.transit.collector.TransGpsCollector tgc =
                    new edu.ilkiv.transit.collector.TransGpsCollector(
                            mock(org.springframework.web.reactive.function.client.WebClient.class),
                            aggregationService
                    );

            edu.ilkiv.transit.dto.TransGpsVehicleDto dto = new edu.ilkiv.transit.dto.TransGpsVehicleDto();
            dto.setImei("test-imei-2");
            dto.setLat(48.29);
            dto.setLng(25.93);
            dto.setSpeed(null);        // null → NPE при trim()
            dto.setOrientation(null);
            dto.setRouteName("10");
            dto.setRouteId(10);
            dto.setOnline(true);

            java.lang.reflect.Method m = TransGpsCollector.class
                    .getDeclaredMethod("toPositionDto",
                            edu.ilkiv.transit.dto.TransGpsVehicleDto.class);
            m.setAccessible(true);
            edu.ilkiv.transit.dto.VehiclePositionDto result =
                    (edu.ilkiv.transit.dto.VehiclePositionDto) m.invoke(tgc, dto);

            assertThat(result.getSpeed()).isEqualTo(0f);
            assertThat(result.getBearing()).isEqualTo(0f);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    static byte[] aesEncrypt(byte[] data) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(AES_KEY, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(AES_IV);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
        return cipher.doFinal(data);
    }

    private static void setPrivateField(Object target, String fieldName, Object value)
            throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}