package com.elitale.coldbirds.coldcalling.telephony;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class NetworkMonitorTest {

    @Test
    void probeNow_delegatesToProbe() {
        NetworkMonitor up = new NetworkMonitor(() -> true, Duration.ofSeconds(5), r -> {});
        NetworkMonitor down = new NetworkMonitor(() -> false, Duration.ofSeconds(5), r -> {});
        assertThat(up.probeNow()).isTrue();
        assertThat(down.probeNow()).isFalse();
    }

    @Test
    void start_deliversProbeResults() throws InterruptedException {
        List<Boolean> got = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        NetworkMonitor monitor = new NetworkMonitor(() -> true, Duration.ofMillis(20), r -> {
            got.add(r);
            latch.countDown();
        });

        monitor.start();
        try {
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(got).contains(true);
        } finally {
            monitor.stop();
        }
    }

    @Test
    void stop_whenNotStarted_isNoOp() {
        new NetworkMonitor(() -> true, Duration.ofSeconds(5), r -> {}).stop();   // must not throw
    }

    @Test
    void probeException_isSwallowed_perTick() {
        // A throwing probe must not kill the scheduler; probeNow propagates, tick swallows.
        NetworkMonitor monitor = new NetworkMonitor(
                () -> { throw new RuntimeException("boom"); }, Duration.ofMillis(20), r -> {});
        monitor.start();
        monitor.stop();   // no assertion — just must not blow up the test thread
    }
}
