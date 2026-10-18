package com.itajay.superassistant.health;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.CheckHealthResponse;
import io.milvus.param.R;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reports the vector store in {@code /actuator/health}.
 *
 * <p>{@code GET /api/health} remains a plain liveness probe. This indicator is the
 * one an orchestrator should use: it actually contacts Milvus, so a running JVM with
 * a dead vector store no longer reports healthy.</p>
 */
@Component("milvus")
public class MilvusHealthIndicator implements HealthIndicator {

    /** Health checks must answer quickly even when the gRPC peer is unreachable. */
    private static final long PROBE_TIMEOUT_SECONDS = 3;
    private static final ExecutorService PROBE_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    private final MilvusServiceClient milvusClient;

    public MilvusHealthIndicator(MilvusServiceClient milvusClient) {
        this.milvusClient = milvusClient;
    }

    @Override
    public Health health() {
        try {
            R<CheckHealthResponse> response = CompletableFuture
                    .supplyAsync(milvusClient::checkHealth, PROBE_EXECUTOR)
                    .get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            CheckHealthResponse data = response.getData();
            boolean ok = response.getStatus() != null
                    && response.getStatus() == R.Status.Success.getCode()
                    && data != null
                    && data.getIsHealthy();
            if (ok) {
                return Health.up().build();
            }
            return Health.down()
                    .withDetail("reason", response.getMessage() == null
                            ? "Milvus reported an unhealthy state" : response.getMessage())
                    .build();
        } catch (TimeoutException e) {
            return Health.down().withDetail("reason", "Milvus health check timed out").build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Health.down(e).build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
