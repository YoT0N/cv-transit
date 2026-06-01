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
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static edu.ilkiv.transit.collector.EasyWayCollectorTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Тести для підвищення METHOD coverage у EasyWayCollector.
 *
 * Цільові методи (зараз 79% = 42/53, потрібно ≥85% = 46/53):
 *   1. mergeChunks()       — static утиліта
 *   2. scheduleReconnect() — ScheduledExecutorService
 *   3. doConnect()         — оркеструє connectOne
 *   4. connectOne()        — success і failure гілки
 *   5. connectTwo() + анонімний Listener (5 методів):
 *        onOpen / onText(success) / onText(fail) / onClose / onError
 *   6. ProtoReader — readVarint64 large value, readString
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EasyWayCollector — method coverage tests")
class EasyWayCollectorMethodsTest {

    private static final byte[] AES_KEY = "k38hdGen3ksqAe3m".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AES_IV  = "k38hdGen3ksqAe3m".getBytes(StandardCharsets.UTF_8);

    @Mock
    VehicleAggregationService aggregationService;

    EasyWayCollector collector;

    @BeforeEach
    void setUp() {
        collector = new EasyWayCollector(aggregationService);
    }

    // ── mergeChunks ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("mergeChunks — via onBinary with multiple fragments")
    class MergeChunksTests {

        @Test
        @DisplayName("mergeChunks — три фрагменти склеюються правильно")
        void mergeChunks_threeChunks_correctOrder() throws Exception {
            WebSocket ws = mock(WebSocket.class);

            byte[] protobuf  = buildProtobufWithVehicle(1, 1, 48_275_470L, 25_929_660L, "001(1111)");
            byte[] encrypted = aesEncrypt(protobuf);

            int size  = encrypted.length / 3;
            byte[] p1 = new byte[size];
            byte[] p2 = new byte[size];
            byte[] p3 = new byte[encrypted.length - 2 * size];
            System.arraycopy(encrypted, 0,       p1, 0, size);
            System.arraycopy(encrypted, size,    p2, 0, size);
            System.arraycopy(encrypted, 2 * size, p3, 0, p3.length);

            collector.onBinary(ws, java.nio.ByteBuffer.wrap(p1), false);
            collector.onBinary(ws, java.nio.ByteBuffer.wrap(p2), false);
            collector.onBinary(ws, java.nio.ByteBuffer.wrap(p3), true);

            verify(aggregationService).processPositions(any());
        }

        @Test
        @DisplayName("mergeChunks — один фрагмент (last=true одразу)")
        void mergeChunks_singleChunk_works() throws Exception {
            WebSocket ws = mock(WebSocket.class);

            byte[] protobuf  = buildProtobufWithVehicle(2, 9, 48_275_470L, 25_929_660L, "002(2222)");
            byte[] encrypted = aesEncrypt(protobuf);

            collector.onBinary(ws, java.nio.ByteBuffer.wrap(encrypted), true);

            verify(aggregationService).processPositions(any());
        }
    }

    // ── scheduleReconnect ────────────────────────────────────────────────────

    @Nested
    @DisplayName("scheduleReconnect — via onClose / onError")
    class ScheduleReconnectTests {

        @Test
        @DisplayName("scheduleReconnect — onClose тригерить schedule")
        void scheduleReconnect_onClose_schedulesReconnect() throws Exception {
            ScheduledExecutorService mockScheduler = mock(ScheduledExecutorService.class);
            when(mockScheduler.isShutdown()).thenReturn(false);
            setPrivateField(collector, "scheduler", mockScheduler);

            collector.onClose(mock(WebSocket.class), 1000, "Normal");

            verify(mockScheduler).schedule(any(Runnable.class), eq(10L), eq(TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("scheduleReconnect — onError тригерить schedule")
        void scheduleReconnect_onError_schedulesReconnect() throws Exception {
            ScheduledExecutorService mockScheduler = mock(ScheduledExecutorService.class);
            when(mockScheduler.isShutdown()).thenReturn(false);
            setPrivateField(collector, "scheduler", mockScheduler);

            collector.onError(mock(WebSocket.class), new RuntimeException("err"));

            verify(mockScheduler).schedule(any(Runnable.class), eq(10L), eq(TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("scheduleReconnect — scheduler зупинений → schedule не викликається")
        void scheduleReconnect_schedulerShutdown_scheduleNotCalled() throws Exception {
            ScheduledExecutorService mockScheduler = mock(ScheduledExecutorService.class);
            when(mockScheduler.isShutdown()).thenReturn(true);
            setPrivateField(collector, "scheduler", mockScheduler);

            collector.onClose(mock(WebSocket.class), 1001, "Going away");

            verify(mockScheduler, never()).schedule(any(Runnable.class), anyLong(), any());
        }
    }

    // ── doConnect ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("doConnect — private orchestration method")
    class DoConnectTests {

        @Test
        @DisplayName("doConnect — не кидає exception при встановленому httpClient")
        void doConnect_withHttpClient_noException() throws Exception {
            HttpClient realClient = HttpClient.newHttpClient();
            setPrivateField(collector, "httpClient", realClient);

            Method doConnect = EasyWayCollector.class.getDeclaredMethod("doConnect");
            doConnect.setAccessible(true);

            assertThatCode(() -> doConnect.invoke(collector)).doesNotThrowAnyException();
        }
    }

    // ── connectOne ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("connectOne — success і failure branches")
    class ConnectOneTests {

        /**
         * buildAsync завершується успішно → webSocket поле встановлюється.
         * Використовуємо WebSocket.Builder (правильний тип від newWebSocketBuilder()).
         */
        @Test
        @DisplayName("connectOne — success → webSocket1 встановлюється")
        void connectOne_success_webSocketSet() throws Exception {
            HttpClient mockClient   = mock(HttpClient.class);
            // RETURNS_SELF вирішує fluent chain: .header().header() → WebSocket.Builder
            WebSocket.Builder mockBuilder = mock(WebSocket.Builder.class, RETURNS_SELF);
            WebSocket mockWs        = mock(WebSocket.class);

            when(mockClient.newWebSocketBuilder()).thenReturn(mockBuilder);
            when(mockBuilder.buildAsync(any(URI.class), any(WebSocket.Listener.class)))
                    .thenReturn(CompletableFuture.completedFuture(mockWs));

            setPrivateField(collector, "httpClient", mockClient);

            Method connectOne = EasyWayCollector.class.getDeclaredMethod("connectOne");
            connectOne.setAccessible(true);
            CompletableFuture<?> future = (CompletableFuture<?>) connectOne.invoke(collector);
            future.join();

            Field wsField = EasyWayCollector.class.getDeclaredField("webSocket");
            wsField.setAccessible(true);
            assertThat(wsField.get(collector)).isSameAs(mockWs);
        }

        /**
         * buildAsync завершується з помилкою → .exceptionally() викликає scheduleReconnect().
         */
        @Test
        @DisplayName("connectOne — failure → scheduleReconnect викликається")
        void connectOne_failure_schedulesReconnect() throws Exception {
            ScheduledExecutorService mockScheduler = mock(ScheduledExecutorService.class);
            when(mockScheduler.isShutdown()).thenReturn(false);
            setPrivateField(collector, "scheduler", mockScheduler);

            HttpClient mockClient     = mock(HttpClient.class);
            WebSocket.Builder mockBuilder = mock(WebSocket.Builder.class, RETURNS_SELF);

            when(mockClient.newWebSocketBuilder()).thenReturn(mockBuilder);

            CompletableFuture<WebSocket> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new RuntimeException("Connection refused"));
            when(mockBuilder.buildAsync(any(URI.class), any(WebSocket.Listener.class)))
                    .thenReturn(failedFuture);

            setPrivateField(collector, "httpClient", mockClient);

            Method connectOne = EasyWayCollector.class.getDeclaredMethod("connectOne");
            connectOne.setAccessible(true);
            CompletableFuture<?> result = (CompletableFuture<?>) connectOne.invoke(collector);
            result.join();

            verify(mockScheduler).schedule(any(Runnable.class), eq(10L), eq(TimeUnit.SECONDS));
        }
    }

    // ── connectTwo — анонімний Listener ──────────────────────────────────────

    @Nested
    @DisplayName("connectTwo — anonymous WebSocket.Listener (5 methods)")
    class ConnectTwoListenerTests {

        /**
         * Витягуємо анонімний Listener через ArgumentCaptor.
         * RETURNS_SELF для fluent .header().header() chain.
         * buildAsync повертає незавершений Future → connectTwo не зависає.
         */
        private WebSocket.Listener extractListener() throws Exception {
            HttpClient mockClient     = mock(HttpClient.class);
            WebSocket.Builder mockBuilder = mock(WebSocket.Builder.class, RETURNS_SELF);

            when(mockClient.newWebSocketBuilder()).thenReturn(mockBuilder);

            ArgumentCaptor<WebSocket.Listener> captor =
                    ArgumentCaptor.forClass(WebSocket.Listener.class);

            // Незавершений Future — connectTwo не чекає і не кидає exception
            when(mockBuilder.buildAsync(any(URI.class), captor.capture()))
                    .thenReturn(new CompletableFuture<>());

            setPrivateField(collector, "httpClient", mockClient);

            Method connectTwo = EasyWayCollector.class.getDeclaredMethod("connectTwo");
            connectTwo.setAccessible(true);
            connectTwo.invoke(collector);

            return captor.getValue();
        }

        @Test
        @DisplayName("connectTwo Listener.onOpen — ws.request(1) викликається")
        void onOpen_requestsCalled() throws Exception {
            WebSocket.Listener listener = extractListener();
            WebSocket ws = mock(WebSocket.class);

            listener.onOpen(ws);

            verify(ws).request(1);
        }

        @Test
        @DisplayName("connectTwo Listener.onText — валідний AES → агрегується")
        void onText_validPayload_aggregated() throws Exception {
            WebSocket.Listener listener = extractListener();
            WebSocket ws = mock(WebSocket.class);

            byte[] msg       = buildProtobufWithVehicle(10, 1, 48_275_470L, 25_929_660L, "001(1010)");
            byte[] encrypted = aesEncrypt(msg);
            String b64       = Base64.getEncoder().encodeToString(encrypted);

            listener.onText(ws, b64, true);

            verify(aggregationService).processPositions(any());
            verify(ws).request(1);
        }

        @Test
        @DisplayName("connectTwo Listener.onText — decrypt fail → не кидає exception")
        void onText_decryptFail_noException() throws Exception {
            WebSocket.Listener listener = extractListener();
            WebSocket ws = mock(WebSocket.class);

            // 3 байти після decode — не кратно 16, AES кине exception
            String b64 = Base64.getEncoder().encodeToString(new byte[]{0x01, 0x02, 0x03});

            assertThatCode(() -> listener.onText(ws, b64, true)).doesNotThrowAnyException();
            verifyNoInteractions(aggregationService);
            verify(ws).request(1);
        }

        @Test
        @DisplayName("connectTwo Listener.onText — last=false → не обробляється")
        void onText_notLast_noProcessing() throws Exception {
            WebSocket.Listener listener = extractListener();
            WebSocket ws = mock(WebSocket.class);

            listener.onText(ws, "SGVsbG8=", false);

            verifyNoInteractions(aggregationService);
            verify(ws).request(1);
        }

        @Test
        @DisplayName("connectTwo Listener.onClose → scheduleReconnect")
        void onClose_schedulesReconnect() throws Exception {
            ScheduledExecutorService mockScheduler = mock(ScheduledExecutorService.class);
            when(mockScheduler.isShutdown()).thenReturn(false);
            setPrivateField(collector, "scheduler", mockScheduler);

            WebSocket.Listener listener = extractListener();

            listener.onClose(mock(WebSocket.class), 1000, "Closed");

            verify(mockScheduler).schedule(any(Runnable.class), eq(10L), eq(TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("connectTwo Listener.onError → scheduleReconnect")
        void onError_schedulesReconnect() throws Exception {
            ScheduledExecutorService mockScheduler = mock(ScheduledExecutorService.class);
            when(mockScheduler.isShutdown()).thenReturn(false);
            setPrivateField(collector, "scheduler", mockScheduler);

            WebSocket.Listener listener = extractListener();

            listener.onError(mock(WebSocket.class), new RuntimeException("ws2 error"));

            verify(mockScheduler).schedule(any(Runnable.class), eq(10L), eq(TimeUnit.SECONDS));
        }
    }

    // ── ProtoReader — readVarint64 & readString ──────────────────────────────

    @Nested
    @DisplayName("ProtoReader — additional method coverage")
    class ProtoReaderMethodTests {

        @Test
        @DisplayName("readVarint64 — великі координати декодуються точно")
        void readVarint64_largeCoords_decodedCorrectly() throws Exception {
            long lat = 48_927_654L;
            long lng = 26_003_211L;

            byte[] msg = buildProtobufWithVehicle(33, 1, lat, lng, "033(3333)");
            invokeProcessMessage(collector, msg);

            ArgumentCaptor<List<VehiclePositionDto>> captor = ArgumentCaptor.forClass(List.class);
            verify(aggregationService).processPositions(captor.capture());

            VehiclePositionDto dto = captor.getValue().get(0);
            assertThat(dto.getLat()).isCloseTo(48.927654, within(0.000001));
            assertThat(dto.getLng()).isCloseTo(26.003211, within(0.000001));
        }

        @Test
        @DisplayName("readString — UTF-8 vehicleLabel читається без exception")
        void readString_utf8Label_noException() throws Exception {
            byte[] vehicleBytes = concat(
                    fieldVarint(6,  44L),
                    fieldLenDelim(7, "044(Авт)".getBytes(StandardCharsets.UTF_8)),
                    fieldVarint(15, 48_275_470L),
                    fieldVarint(16, 25_929_660L)
            );
            byte[] routePositions = fieldLenDelim(1, vehicleBytes);
            byte[] routeEntry     = concat(fieldVarint(1, 1L), fieldLenDelim(2, routePositions));
            byte[] msg            = concat(
                    fieldVarint(1, System.currentTimeMillis() / 1000),
                    fieldLenDelim(2, routeEntry)
            );

            assertThatCode(() -> invokeProcessMessage(collector, msg)).doesNotThrowAnyException();
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

    static void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    static void invokeProcessMessage(Object target, byte[] data) throws Exception {
        Method m = EasyWayCollector.class.getDeclaredMethod("processMessage", byte[].class);
        m.setAccessible(true);
        m.invoke(target, (Object) data);
    }
}