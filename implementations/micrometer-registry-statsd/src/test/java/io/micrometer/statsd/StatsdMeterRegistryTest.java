/**
 * Copyright 2017 Pivotal Software, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.statsd;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.micrometer.core.Issue;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.logging.LogbackMetrics;
import io.micrometer.core.lang.Nullable;
import io.netty.channel.ChannelOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Operators;
import reactor.ipc.netty.options.ClientOptions;
import reactor.ipc.netty.udp.UdpServer;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Jon Schneider
 */
@Disabled("Flakiness on CI -- perhaps because UdpServer is not being shut down as deterministically as we think?")
class StatsdMeterRegistryTest {
    /**
     * A port that is NOT the default for DogStatsD or Telegraf, so these unit tests
     * do not fail if one of those agents happens to be running on the same box.
     */
    private static final int PORT = 8126;
    private MockClock clock = new MockClock();
    private Duration step = Duration.ofMillis(5);

    @BeforeAll
    static void before() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.INFO);
    }

    private void assertLines(Consumer<StatsdMeterRegistry> registryAction, StatsdFlavor flavor, String... expected) {
        final CountDownLatch bindLatch = new CountDownLatch(1);
        final CountDownLatch receiveLatch = new CountDownLatch(expected.length);
        final CountDownLatch terminateLatch = new CountDownLatch(1);

        final Disposable.Swap server = Disposables.swap();

        final StatsdMeterRegistry registry = registry(flavor);

        Consumer<ClientOptions.Builder<?>> opts = builder -> builder.option(ChannelOption.SO_REUSEADDR, true)
            .connectAddress(() -> new InetSocketAddress(PORT));

        UdpServer.create(opts)
            .newHandler((in, out) -> {
                in.receive()
                    .asString()
                    .filter(line -> !line.toLowerCase().startsWith("statsd")) // ignore gauges monitoring the registry itself
                    .log()
                    .subscribe(line -> {
                        for (String s : line.split("\n")) {
                            for (String s1 : expected) {
                                if(s.equals(s1))
                                    receiveLatch.countDown();
                            }
                        }
                    });
                return Flux.never();
            })
            .doOnSuccess(v -> bindLatch.countDown())
            .doOnTerminate(terminateLatch::countDown)
            .subscribe(server::replace);

        try {
            assertTrue(bindLatch.await(1, TimeUnit.SECONDS));

            try {
                registryAction.accept(registry);
            } catch (Throwable t) {
                fail("Failed to perform registry action", t);
            }

            assertTrue(receiveLatch.await(3, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            fail("Failed to wait for line", e);
        } finally {
            server.dispose();
            registry.stop();
            try {
                terminateLatch.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                fail("Failed to terminate UDP server listening for StatsD messages", e);
            }
        }
    }

    private StatsdMeterRegistry registry(StatsdFlavor flavor) {
        return new StatsdMeterRegistry(new StatsdConfig() {
            @Override
            @Nullable
            public String get(String key) {
                return null;
            }

            @Override
            public int port() {
                return PORT;
            }

            @Override
            public StatsdFlavor flavor() {
                return flavor;
            }

            @Override
            public Duration step() {
                return step;
            }

            @Override
            public Duration pollingFrequency() {
                return step;
            }
        }, clock);
    }

    @ParameterizedTest
    @EnumSource(StatsdFlavor.class)
    void counterLineProtocol(StatsdFlavor flavor) {
        String line = null;
        switch (flavor) {
            case ETSY:
                line = "myCounter.myTag.val.statistic.count:2|c";
                break;
            case DATADOG:
                line = "my.counter:2|c|#statistic:count,my.tag:val";
                break;
            case TELEGRAF:
                line = "my_counter,statistic=count,my_tag=val:2|c";
        }

        assertLines(r -> r.counter("my.counter", "my.tag", "val").increment(2.1), flavor, line);
    }

    @ParameterizedTest
    @EnumSource(StatsdFlavor.class)
    void gaugeLineProtocol(StatsdFlavor flavor) {
        String line = null;
        switch (flavor) {
            case ETSY:
                line = "myGauge.myTag.val.statistic.value:2|g";
                break;
            case DATADOG:
                line = "my.gauge:2|g|#statistic:value,my.tag:val";
                break;
            case TELEGRAF:
                line = "my_gauge,statistic=value,my_tag=val:2|g";
                break;
        }

        Integer n = 2;
        assertLines(r -> r.gauge("my.gauge", Tags.of("my.tag", "val"), n), flavor, line);
    }

    @ParameterizedTest
    @EnumSource(StatsdFlavor.class)
    void timerLineProtocol(StatsdFlavor flavor) {
        String line = null;
        switch (flavor) {
            case ETSY:
                line = "myTimer.myTag.val:1|ms";
                break;
            case DATADOG:
                line = "my.timer:1|ms|#my.tag:val";
                break;
            case TELEGRAF:
                line = "my_timer,my_tag=val:1|ms";
        }

        assertLines(r -> r.timer("my.timer", "my.tag", "val").record(1, TimeUnit.MILLISECONDS),
            flavor, line);
    }

    @ParameterizedTest
    @EnumSource(StatsdFlavor.class)
    void summaryLineProtocol(StatsdFlavor flavor) {
        String line = null;
        switch (flavor) {
            case ETSY:
                line = "mySummary.myTag.val:1|h";
                break;
            case DATADOG:
                line = "my.summary:1|h|#my.tag:val";
                break;
            case TELEGRAF:
                line = "my_summary,my_tag=val:1|h";
        }

        assertLines(r -> r.summary("my.summary", "my.tag", "val").record(1), flavor, line);
    }

    @ParameterizedTest
    @EnumSource(StatsdFlavor.class)
    void longTaskTimerLineProtocol(StatsdFlavor flavor) {
        final Function<MeterRegistry, LongTaskTimer> ltt = r -> r.more().longTaskTimer("my.long.task", "my.tag", "val");

        StepVerifier
            .withVirtualTime(() -> {
                String[] lines = null;
                switch (flavor) {
                    case ETSY:
                        lines = new String[]{
                            "myLongTask.myTag.val.statistic.activetasks:1|c",
                            "myLongTaskDuration.myTag.val.statistic.value:1|c",
                        };
                        break;
                    case DATADOG:
                        lines = new String[]{
                            "my.long.task:1|c|#statistic:activetasks,myTag:val",
                            "my.long.task:1|c|#statistic:duration,myTag:val",
                        };
                        break;
                    case TELEGRAF:
                        lines = new String[]{
                            "myLongTask,statistic=activetasks,myTag=val:1|c",
                            "myLongTask,statistic=duration,myTag=val:1|c",
                        };
                }

                assertLines(r -> ltt.apply(r).start(), flavor, lines);
                return null;
            })
            .then(() -> clock.add(10, TimeUnit.MILLISECONDS))
            .thenAwait(Duration.ofMillis(10));
    }

    @Test
    void customNamingConvention() {
        AtomicInteger n = new AtomicInteger(1);

        assertLines(r -> {
            r.gauge("my.gauge", n);
            r.config().namingConvention((name, type, baseUnit) -> name.toUpperCase());
            n.addAndGet(1);
        }, StatsdFlavor.ETSY, "MY.GAUGE.statistic.value:2|g");
    }

    @Issue("#411")
    @Test
    void counterIncrementDoesNotCauseStackOverflow() {
        StatsdMeterRegistry registry = registry(StatsdFlavor.ETSY);
        new LogbackMetrics().bindTo(registry);

        // Cause the publisher to get into a state that would make it perform logging at DEBUG level.
        ((Logger) LoggerFactory.getLogger(Operators.class)).setLevel(Level.DEBUG);
        registry.publisher.onComplete();

        registry.counter("my.counter").increment();
    }

    @ParameterizedTest
    @EnumSource(StatsdFlavor.class)
    @Issue("#370")
    void slasOnlyNoPercentileHistogram(StatsdFlavor flavor) {
        MeterRegistry registry = registry(flavor);
        DistributionSummary summary = DistributionSummary.builder("my.summary").sla(1, 2).register(registry);
        summary.record(1);

        Timer timer = Timer.builder("my.timer").sla(Duration.ofMillis(1)).register(registry);
        timer.record(1, TimeUnit.MILLISECONDS);

        Gauge summaryHist1 = registry.get("my.summary.histogram").tags("le", "1").gauge();
        Gauge summaryHist2 = registry.get("my.summary.histogram").tags("le", "2").gauge();
        Gauge timerHist = registry.get("my.timer.histogram").tags("le", "1").gauge();

        assertThat(summaryHist1.value()).isEqualTo(1);
        assertThat(summaryHist2.value()).isEqualTo(1);
        assertThat(timerHist.value()).isEqualTo(1);

        clock.add(step);

        assertThat(summaryHist1.value()).isEqualTo(0);
        assertThat(summaryHist2.value()).isEqualTo(0);
        assertThat(timerHist.value()).isEqualTo(0);
    }
}
