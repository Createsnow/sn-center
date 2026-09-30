package com.adtek.server;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** 容器探针仅查询本进程固定loopback就绪地址，不接收任意URL。 */
final class HealthCommand {
    private HealthCommand() {}

    static int run() {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            int port = Integer.parseInt(System.getenv().getOrDefault("ADTEK_SERVER_PORT", "18080"));
            var response =
                    client.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "http://127.0.0.1:"
                                                            + port
                                                            + "/actuator/health/readiness"))
                                    .timeout(Duration.ofSeconds(3))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains("\"UP\"") ? 0 : 1;
        } catch (Exception failure) {
            return 1;
        }
    }
}
