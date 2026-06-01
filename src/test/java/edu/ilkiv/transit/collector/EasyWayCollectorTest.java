package edu.ilkiv.transit.collector;

import edu.ilkiv.transit.dto.VehiclePositionDto;
import edu.ilkiv.transit.model.DataSource;
import edu.ilkiv.transit.model.TransportType;
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
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.net.http.WebSocket;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EasyWayCollector — unit tests")
class EasyWayCollectorTest {

    private static final byte[] AES_KEY = "k38hdGen3ksqAe3m".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AES_IV  = "k38hdGen3ksqAe3m".getBytes(StandardCharsets.UTF_8);

    @Mock
    VehicleAggregationService aggregationService;

    EasyWayCollector collector;

    @BeforeEach
    void setUp() {
        collector = new EasyWayCollector(aggregationService);
    }

    // ── AES decrypt ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AES decrypt")
    class AesDecryptTests {

        @Test
        @DisplayName("Коректно розшифровує AES-128-CBC дані")
        void aesDecrypt_validData_returnsPlaintext() throws Exception {
            byte[] original  = "Hello, EasyWay!".getBytes(StandardCharsets.UTF_8);
            byte[] encrypted = aesEncrypt(original);
            byte[] result    = invokeAesDecrypt(encrypted);
            assertThat(result).startsWith(original);
        }

        @Test
        @DisplayName("Base64 → AES decrypt pipeline — round trip")
        void aesDecrypt_base64Pipeline_roundTrip() throws Exception {
            byte[] original  = new byte[]{0x08, 0x01, 0x12, 0x04, 0x08, 0x05};
            byte[] encrypted = aesEncrypt(original);
            String base64    = Base64.getEncoder().encodeToString(encrypted);
            byte[] decrypted = invokeAesDecrypt(Base64.getDecoder().decode(base64));
            assertThat(decrypted).startsWith(original);
        }

        @Test
        @DisplayName("Невалідні байти — кидає exception")
        void aesDecrypt_invalidData_throwsException() {
            byte[] garbage = new byte[]{0x01, 0x02, 0x03, 0x04};
            assertThatThrownBy(() -> invokeAesDecrypt(garbage))
                    .isInstanceOf(Exception.class);
        }
    }

    // ── tryDecrypt ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("tryDecrypt")
    class TryDecryptTests {

        @Test
        @DisplayName("Валідні зашифровані дані — повертає розшифровані")
        void tryDecrypt_validEncrypted_returnsDecrypted() throws Exception {
            byte[] original  = "test data".getBytes(StandardCharsets.UTF_8);
            byte[] encrypted = aesEncrypt(original);
            byte[] result    = invokeTryDecrypt(encrypted);
            assertThat(result).startsWith(original);
        }

        @Test
        @DisplayName("Невалідні байти — повертає оригінал без exception")
        void tryDecrypt_invalidData_returnsOriginal() throws Exception {
            byte[] garbage = new byte[]{0x01, 0x02, 0x03};
            byte[] result  = invokeTryDecrypt(garbage);
            assertThat(result).isEqualTo(garbage);
        }
    }

    // ── extractBusNumber ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("extractBusNumber")
    class ExtractBusNumberTests {

        @Test
        @DisplayName("'075(6351)' → '6351'")
        void standardFormat() throws Exception {
            assertThat(invokeExtractBusNumber("075(6351)")).isEqualTo("6351");
        }

        @Test
        @DisplayName("'001(0042)' → '0042' (провідні нулі збережені)")
        void leadingZeros() throws Exception {
            assertThat(invokeExtractBusNumber("001(0042)")).isEqualTo("0042");
        }

        @Test
        @DisplayName("Без дужок — повертає рядок як є")
        void noBrackets() throws Exception {
            assertThat(invokeExtractBusNumber("12345")).isEqualTo("12345");
        }

        @Test
        @DisplayName("null → null")
        void nullInput() throws Exception {
            assertThat(invokeExtractBusNumber(null)).isNull();
        }

        @Test
        @DisplayName("Порожній рядок → ''")
        void emptyString() throws Exception {
            assertThat(invokeExtractBusNumber("")).isEqualTo("");
        }

        @Test
        @DisplayName("'()' → ''")
        void emptyBrackets() throws Exception {
            assertThat(invokeExtractBusNumber("()")).isEqualTo("");
        }

        @Test
        @DisplayName("Тільки відкрита дужка — повертає як є")
        void onlyOpenBracket() throws Exception {
            assertThat(invokeExtractBusNumber("123(456")).isEqualTo("123(456");
        }
    }

    // ── processMessage — базова фільтрація ────────────────────────────────────

    @Nested
    @DisplayName("processMessage — фільтрація")
    class ProcessMessageFilterTests {

        @Test
        @DisplayName("Порожній масив — нічого не передається")
        void emptyBytes_noAggregation() throws Exception {
            invokeProcessMessage(collector, new byte[0]);
            verifyNoInteractions(aggregationService);
        }

        @Test
        @DisplayName("Один байт — нічого не передається")
        void singleByte_noAggregation() throws Exception {
            invokeProcessMessage(collector, new byte[]{0x00});
            verifyNoInteractions(aggregationService);
        }

        @Test
        @DisplayName("Нульові координати (0,0) — vehicle не передається")
        void zeroCoordinates_notAggregated() throws Exception {
            invokeProcessMessage(collector, new byte[]{0x08, 0x00});
            verifyNoInteractions(aggregationService);
        }

        @Test
        @DisplayName("Невідомий routeId — vehicle не передається")
        void unknownRouteId_notAggregated() throws Exception {
            byte[] msg = buildProtobufWithVehicle(
                    999, 999, 48_275_470L, 25_929_660L, "999(9999)");
            invokeProcessMessage(collector, msg);
            verifyNoInteractions(aggregationService);
        }

        @Test
        @DisplayName("Координати за межами Чернівців (lat < 47.5) — не передаються")
        void latTooSmall_notAggregated() throws Exception {
            byte[] msg = buildProtobufWithVehicle(
                    100, 1, 46_000_000L, 25_929_660L, "001(1111)");
            invokeProcessMessage(collector, msg);
            verifyNoInteractions(aggregationService);
        }

        @Test
        @DisplayName("Координати за межами Чернівців (lng > 27.0) — не передаються")
        void lngTooLarge_notAggregated() throws Exception {
            byte[] msg = buildProtobufWithVehicle(
                    100, 1, 48_275_470L, 28_000_000L, "001(1111)");
            invokeProcessMessage(collector, msg);
            verifyNoInteractions(aggregationService);
        }

        @Test
        @DisplayName("vehicleId < 0 (відсутній) — vehicle не передається")
        void missingVehicleId_notAggregated() throws Exception {
            // VehicleBlock без поля 6 (vehicleId) — id залишається -1
            byte[] vehicleBytes = concat(
                    fieldLenDelim(7, label("???(?)")),
                    fieldVarint(15, 48_275_470L),
                    fieldVarint(16, 25_929_660L)
            );
            byte[] routePositions = fieldLenDelim(1, vehicleBytes);
            byte[] routeEntry = concat(
                    fieldVarint(1, 1L),
                    fieldLenDelim(2, routePositions)
            );
            byte[] msg = concat(
                    fieldVarint(1, System.currentTimeMillis() / 1000),
                    fieldLenDelim(2, routeEntry)
            );
            invokeProcessMessage(collector, msg);
            verifyNoInteractions(aggregationService);
        }
    }

    // ── processMessage — валідний vehicle ─────────────────────────────────────

    @Nested
    @DisplayName("processMessage — валідний vehicle")
    class ProcessMessageValidTests {

        @Test
        @DisplayName("Відомий routeId=1 (тролейбус 1) — vehicle передається")
        void knownTrollRoute_aggregated() throws Exception {
            byte[] msg = buildProtobufWithVehicle(
                    42, 1, 48_275_470L, 25_929_660L, "001(4242)");
            invokeProcessMessage(collector, msg);

            ArgumentCaptor<List<VehiclePositionDto>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(aggregationService).processPositions(captor.capture());

            VehiclePositionDto dto = captor.getValue().get(0);
            assertThat(dto.getSource()).isEqualTo(DataSource.easyway);
            assertThat(dto.getType()).isEqualTo(TransportType.TROLL);
            assertThat(dto.getRouteName()).isEqualTo("1");
            assertThat(dto.getLat()).isCloseTo(48.27547, within(0.0001));
            assertThat(dto.getLng()).isCloseTo(25.92966, within(0.0001));
            assertThat(dto.getBusNumber()).isEqualTo("4242");
            assertThat(dto.getOnline()).isTrue();
        }

        @Test
        @DisplayName("Відомий routeId=9 (автобус 3) — тип BUS")
        void knownBusRoute_typeBus() throws Exception {
            byte[] msg = buildProtobufWithVehicle(
                    55, 9, 48_275_470L, 25_929_660L, "009(5555)");
            invokeProcessMessage(collector, msg);

            ArgumentCaptor<List<VehiclePositionDto>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(aggregationService).processPositions(captor.capture());

            assertThat(captor.getValue().get(0).getType()).isEqualTo(TransportType.BUS);
        }

        @Test
        @DisplayName("externalId = vehicleId як рядок")
        void externalId_isVehicleId() throws Exception {
            byte[] msg = buildProtobufWithVehicle(
                    777, 1, 48_275_470L, 25_929_660L, "001(7777)");
            invokeProcessMessage(collector, msg);

            ArgumentCaptor<List<VehiclePositionDto>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(aggregationService).processPositions(captor.capture());

            assertThat(captor.getValue().get(0).getExternalId()).isEqualTo("777");
        }

        @Test
        @DisplayName("busNumber витягується з label формату 'XXX(NNNN)'")
        void busNumber_extractedFromLabel() throws Exception {
            byte[] msg = buildProtobufWithVehicle(
                    10, 1, 48_275_470L, 25_929_660L, "075(6351)");
            invokeProcessMessage(collector, msg);

            ArgumentCaptor<List<VehiclePositionDto>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(aggregationService).processPositions(captor.capture());

            assertThat(captor.getValue().get(0).getBusNumber()).isEqualTo("6351");
        }

        @Test
        @DisplayName("externalRouteId передається правильно")
        void externalRouteId_correct() throws Exception {
            byte[] msg = buildProtobufWithVehicle(
                    10, 1, 48_275_470L, 25_929_660L, "001(1000)");
            invokeProcessMessage(collector, msg);

            ArgumentCaptor<List<VehiclePositionDto>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(aggregationService).processPositions(captor.capture());

            assertThat(captor.getValue().get(0).getExternalRouteId()).isEqualTo("1");
        }

        @Test
        @DisplayName("routeId=75 (тролейбус 8) — тип TROLL, routeName='8'")
        void routeId75_troll8() throws Exception {
            byte[] msg = buildProtobufWithVehicle(
                    20, 75, 48_275_470L, 25_929_660L, "075(2000)");
            invokeProcessMessage(collector, msg);

            ArgumentCaptor<List<VehiclePositionDto>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(aggregationService).processPositions(captor.capture());

            VehiclePositionDto dto = captor.getValue().get(0);
            assertThat(dto.getType()).isEqualTo(TransportType.TROLL);
            assertThat(dto.getRouteName()).isEqualTo("8");
        }

        @Test
        @DisplayName("Кілька vehicles в одному повідомленні — всі передаються")
        void multipleVehicles_allAggregated() throws Exception {
            byte[] v1 = buildVehicleBlock(10, 48_275_470L, 25_929_660L, "001(1001)");
            byte[] v2 = buildVehicleBlock(20, 48_280_000L, 25_940_000L, "002(1002)");

            byte[] routePositions = concat(
                    fieldLenDelim(1, v1),
                    fieldLenDelim(1, v2)
            );
            byte[] routeEntry = concat(
                    fieldVarint(1, 1L),
                    fieldLenDelim(2, routePositions)
            );
            byte[] msg = concat(
                    fieldVarint(1, System.currentTimeMillis() / 1000),
                    fieldLenDelim(2, routeEntry)
            );

            invokeProcessMessage(collector, msg);

            ArgumentCaptor<List<VehiclePositionDto>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(aggregationService).processPositions(captor.capture());
            assertThat(captor.getValue()).hasSize(2);
        }

        @Test
        @DisplayName("Кілька RouteEntry в одному повідомленні — vehicles з різних маршрутів")
        void multipleRouteEntries_allAggregated() throws Exception {
            byte[] v1 = buildVehicleBlock(10, 48_275_470L, 25_929_660L, "001(1001)");
            byte[] v2 = buildVehicleBlock(20, 48_280_000L, 25_940_000L, "002(1002)");

            byte[] routePositions1 = fieldLenDelim(1, v1);
            byte[] routeEntry1 = concat(fieldVarint(1, 1L), fieldLenDelim(2, routePositions1));

            byte[] routePositions2 = fieldLenDelim(1, v2);
            byte[] routeEntry2 = concat(fieldVarint(1, 9L), fieldLenDelim(2, routePositions2));

            byte[] msg = concat(
                    fieldVarint(1, System.currentTimeMillis() / 1000),
                    fieldLenDelim(2, routeEntry1),
                    fieldLenDelim(2, routeEntry2)
            );

            invokeProcessMessage(collector, msg);

            ArgumentCaptor<List<VehiclePositionDto>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(aggregationService).processPositions(captor.capture());
            assertThat(captor.getValue()).hasSize(2);
        }

        @Test
        @DisplayName("Всі опціональні поля vehicle (speed, angle) — не кидає exception")
        void allOptionalVehicleFields_noException() throws Exception {
            // Будуємо vehicle з усіма полями включаючи statusCode, speed, angle
            byte[] vehicleBytes = concat(
                    fieldVarint(1,  0L),   // statusCode = online
                    fieldVarint(6,  99L),  // vehicleId
                    fieldLenDelim(7, label("099(9999)")),
                    fieldVarint(8,  270L), // angle
                    fieldVarint(12, 45L),  // speed
                    fieldVarint(15, 48_275_470L),
                    fieldVarint(16, 25_929_660L)
            );
            byte[] routePositions = fieldLenDelim(1, vehicleBytes);
            byte[] routeEntry = concat(fieldVarint(1, 1L), fieldLenDelim(2, routePositions));
            byte[] msg = concat(
                    fieldVarint(1, System.currentTimeMillis() / 1000),
                    fieldLenDelim(2, routeEntry)
            );

            assertThatCode(() -> invokeProcessMessage(collector, msg))
                    .doesNotThrowAnyException();
            verify(aggregationService).processPositions(any());
        }
    }

    // ── onText / onBinary / onClose / onError ─────────────────────────────────

    @Nested
    @DisplayName("WebSocket listener callbacks")
    class WebSocketListenerTests {

        @Test
        @DisplayName("onOpen — не кидає exception")
        void onOpen_noException() {
            WebSocket ws = mock(WebSocket.class);
            assertThatCode(() -> collector.onOpen(ws)).doesNotThrowAnyException();
            verify(ws).request(1);
        }

        @Test
        @DisplayName("onText — невалідний base64 — не кидає exception")
        void onText_invalidBase64_noException() {
            WebSocket ws = mock(WebSocket.class);
            assertThatCode(() -> collector.onText(ws, "not-valid-base64!!!", true))
                    .doesNotThrowAnyException();
            verify(ws).request(1);
        }

        @Test
        @DisplayName("onText — фрагментоване повідомлення (last=false, last=true)")
        void onText_fragmentedMessage_assembled() throws Exception {
            WebSocket ws = mock(WebSocket.class);
            // Шифруємо валідний protobuf і розбиваємо base64 на два шматки
            byte[] msg = buildProtobufWithVehicle(42, 1, 48_275_470L, 25_929_660L, "001(4242)");
            byte[] encrypted = aesEncrypt(msg);
            String b64 = Base64.getEncoder().encodeToString(encrypted);

            String part1 = b64.substring(0, b64.length() / 2);
            String part2 = b64.substring(b64.length() / 2);

            collector.onText(ws, part1, false);
            verifyNoInteractions(aggregationService);

            collector.onText(ws, part2, true);
            verify(aggregationService).processPositions(any());
        }

        @Test
        @DisplayName("onBinary — невалідні байти (не AES і не protobuf) — не кидає exception")
        void onBinary_invalidBytes_noException() throws Exception {
            WebSocket ws = mock(WebSocket.class);
            byte[] data = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05};
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data);
            assertThatCode(() -> collector.onBinary(ws, buf, true)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("onClose — не кидає exception")
        void onClose_noException() {
            WebSocket ws = mock(WebSocket.class);
            assertThatCode(() -> collector.onClose(ws, 1000, "Normal")).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("onError — не кидає exception")
        void onError_noException() {
            WebSocket ws = mock(WebSocket.class);
            assertThatCode(() -> collector.onError(ws, new RuntimeException("test")))
                    .doesNotThrowAnyException();
        }
    }

    // ── disconnect ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("disconnect — без активного підключення — не кидає exception")
    void disconnect_withoutConnection_noException() {
        assertThatCode(() -> collector.disconnect()).doesNotThrowAnyException();
    }

    // ── Reflection helpers ────────────────────────────────────────────────────

    static byte[] invokeAesDecrypt(byte[] data) throws Exception {
        Method m = EasyWayCollector.class.getDeclaredMethod("aesDecrypt", byte[].class);
        m.setAccessible(true);
        try {
            return (byte[]) m.invoke(null, (Object) data);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    static byte[] invokeTryDecrypt(byte[] data) throws Exception {
        Method m = EasyWayCollector.class.getDeclaredMethod("tryDecrypt", byte[].class);
        m.setAccessible(true);
        return (byte[]) m.invoke(null, (Object) data);
    }

    static String invokeExtractBusNumber(String label) throws Exception {
        Method m = EasyWayCollector.class.getDeclaredMethod("extractBusNumber", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, label);
    }

    static void invokeProcessMessage(Object collector, byte[] data) throws Exception {
        Method m = EasyWayCollector.class.getDeclaredMethod("processMessage", byte[].class);
        m.setAccessible(true);
        m.invoke(collector, (Object) data);
    }

    // ── AES encrypt (для підготовки тестових даних) ───────────────────────────

    static byte[] aesEncrypt(byte[] data) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(AES_KEY, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(AES_IV);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
        return cipher.doFinal(data);
    }

    // ── Protobuf builder helpers (ВИПРАВЛЕНІ) ─────────────────────────────────

    /**
     * Будує повне EasyWay protobuf повідомлення з одним vehicle.
     * Структура: Message → RouteEntry → RoutePositions → VehiclePosition
     */
    static byte[] buildProtobufWithVehicle(
            int vehicleId, int routeId, long lat, long lng, String label) {

        byte[] vehicleBytes   = buildVehicleBlock(vehicleId, lat, lng, label);
        // RoutePositions: field 1 = VehiclePosition (length-delimited)
        byte[] routePositions = fieldLenDelim(1, vehicleBytes);
        // RouteEntry: field 1 = routeId (varint), field 2 = RoutePositions (length-delimited)
        byte[] routeEntry     = concat(
                fieldVarint(1, (long) routeId),
                fieldLenDelim(2, routePositions)
        );
        // Message: field 1 = timestamp (varint), field 2 = RouteEntry (length-delimited)
        return concat(
                fieldVarint(1, System.currentTimeMillis() / 1000),
                fieldLenDelim(2, routeEntry)
        );
    }

    /**
     * Будує блок VehiclePosition.
     */
    static byte[] buildVehicleBlock(int vehicleId, long lat, long lng, String labelStr) {
        return concat(
                fieldVarint(6,  (long) vehicleId),
                fieldLenDelim(7, label(labelStr)),
                fieldVarint(8,  0L),   // angle
                fieldVarint(12, 0L),   // speed
                fieldVarint(15, lat),
                fieldVarint(16, lng)
        );
    }

    // ── Низькорівневі Protobuf-будівники ──────────────────────────────────────

    /** Кодує field з wire type 0 (varint). */
    static byte[] fieldVarint(int fieldNum, long value) {
        byte[] tag  = varint((long) fieldNum << 3 | 0); // wire type 0
        byte[] val  = varint(value);
        return concat(tag, val);
    }

    /** Кодує field з wire type 2 (length-delimited) з готовим byte[]. */
    static byte[] fieldLenDelim(int fieldNum, byte[] data) {
        byte[] tag    = varint((long) fieldNum << 3 | 2); // wire type 2
        byte[] lenPfx = varint(data.length);
        byte[] result = new byte[tag.length + lenPfx.length + data.length];
        System.arraycopy(tag,    0, result, 0,                          tag.length);
        System.arraycopy(lenPfx, 0, result, tag.length,                 lenPfx.length);
        System.arraycopy(data,   0, result, tag.length + lenPfx.length, data.length);
        return result;
    }

    /** Кодує рядок як UTF-8 byte[]. */
    static byte[] label(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] varint(long value) {
        byte[] buf = new byte[10];
        int pos = 0;
        do {
            byte b = (byte) (value & 0x7F);
            value >>>= 7;
            if (value != 0) b |= 0x80;
            buf[pos++] = b;
        } while (value != 0);
        byte[] result = new byte[pos];
        System.arraycopy(buf, 0, result, 0, pos);
        return result;
    }

    static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }

    // Залишаємо для сумісності зі старими тестами що можуть використовувати ці методи
    static byte[] lengthDelimited(byte[] data) {
        byte[] len = varint(data.length);
        byte[] result = new byte[len.length + data.length];
        System.arraycopy(len, 0, result, 0, len.length);
        System.arraycopy(data, 0, result, len.length, data.length);
        return result;
    }
}