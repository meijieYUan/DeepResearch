package com.itajay.superassistant.tool;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** No host may hold a research run past its deadline, however it stalls. */
class HttpSendTest {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private static HttpRequest get(ServerSocket server) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getLocalPort() + "/x")).build();
    }

    /** Accepts the connection, reads the request, and then says nothing at all. */
    private static void stall(ServerSocket server) {
        Thread holder = new Thread(() -> {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(2_000);
                new BufferedReader(new InputStreamReader(socket.getInputStream())).readLine();
                Thread.sleep(5_000);
            } catch (Exception ignored) {
                // The test closes the socket underneath it.
            }
        });
        holder.setDaemon(true);
        holder.start();
    }

    @Test
    void aServerThatNeverAnswersIsAbandonedAtTheDeadline() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            stall(server);
            long start = System.nanoTime();

            assertThatThrownBy(() -> HttpSend.send(client, get(server),
                    HttpResponse.BodyHandlers.ofString(), Duration.ofMillis(300)))
                    .isInstanceOf(TimeoutException.class)
                    .hasMessageContaining("300");
            assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(3_000);
        }
    }

    @Test
    void aNormalResponseStillComesThrough() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread responder = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(2_000);
                    new BufferedReader(new InputStreamReader(socket.getInputStream())).readLine();
                    OutputStream out = socket.getOutputStream();
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                            .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    out.flush();
                } catch (Exception ignored) {
                    // best effort
                }
            });
            responder.setDaemon(true);
            responder.start();

            HttpResponse<String> response = HttpSend.send(client, get(server),
                    HttpResponse.BodyHandlers.ofString(), Duration.ofSeconds(5));

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("ok");
        }
    }

    @Test
    void aRefusedConnectionIsReportedAsAnIoFailureNotATimeout() throws Exception {
        int port;
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = server.getLocalPort();
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/x")).build();

        assertThatThrownBy(() -> HttpSend.send(client, request,
                HttpResponse.BodyHandlers.ofString(), Duration.ofSeconds(5)))
                .isInstanceOf(java.io.IOException.class)
                .isNotInstanceOf(TimeoutException.class);
    }
}
