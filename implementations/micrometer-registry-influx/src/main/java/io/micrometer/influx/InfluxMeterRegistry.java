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
package io.micrometer.influx;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.config.NamingConvention;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.step.StepMeterRegistry;
import io.micrometer.core.instrument.util.DoubleFormat;
import io.micrometer.core.instrument.util.MeterPartition;
import io.micrometer.core.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

/**
 * @author Jon Schneider
 */
public class InfluxMeterRegistry extends StepMeterRegistry {
    private final InfluxConfig config;
    private final Logger logger = LoggerFactory.getLogger(InfluxMeterRegistry.class);

    public InfluxMeterRegistry(InfluxConfig config, Clock clock, ThreadFactory threadFactory) {
        super(config, clock);
        this.config().namingConvention(new InfluxNamingConvention(NamingConvention.snakeCase));
        this.config = config;
        start(threadFactory);
    }

    public InfluxMeterRegistry(InfluxConfig config, Clock clock) {
        this(config, clock, Executors.defaultThreadFactory());
    }

    private void createDatabaseIfNecessary() {
        if (!config.autoCreateDb())
            return;

        HttpURLConnection con = null;
        try {
            URL queryEndpoint = URI.create(config.uri() + "/query?q=" + URLEncoder.encode("CREATE DATABASE \"" + config.db() + "\"", "UTF-8")).toURL();

            con = (HttpURLConnection) queryEndpoint.openConnection();
            con.setConnectTimeout((int) config.connectTimeout().toMillis());
            con.setReadTimeout((int) config.readTimeout().toMillis());
            con.setRequestMethod("POST");
            authenticateRequest(con);

            int status = con.getResponseCode();

            if (status >= 200 && status < 300) {
                logger.debug("influx database {} is ready to receive metrics", config.db());
            } else if (status >= 400) {
                try (InputStream in = con.getErrorStream()) {
                    logger.error("unable to create database '{}': {}", config.db(), new BufferedReader(new InputStreamReader(in))
                            .lines().collect(joining("\n")));
                }
            }
        } catch (IOException e) {
            logger.warn("unable to create database '{}'", config.db(), e);
        } finally {
            quietlyCloseUrlConnection(con);
        }
    }

    @Override
    protected void publish() {
        createDatabaseIfNecessary();

        try {
            String write = "/write?consistency=" + config.consistency().toString().toLowerCase() + "&precision=ms&db=" + config.db();
            if (config.retentionPolicy() != null) {
                write += "&rp=" + config.retentionPolicy();
            }
            URL influxEndpoint = URI.create(config.uri() + write).toURL();
            HttpURLConnection con = null;

            for (List<Meter> batch : MeterPartition.partition(this, config.batchSize())) {
                try {
                    con = (HttpURLConnection) influxEndpoint.openConnection();
                    con.setConnectTimeout((int) config.connectTimeout().toMillis());
                    con.setReadTimeout((int) config.readTimeout().toMillis());
                    con.setRequestMethod("POST");
                    con.setRequestProperty("Content-Type", "plain/text");
                    con.setDoOutput(true);

                    authenticateRequest(con);

                    List<String> bodyLines = batch.stream()
                            .map(m -> {
                                if (m instanceof Timer) {
                                    return writeTimer((Timer) m);
                                }
                                if (m instanceof DistributionSummary) {
                                    return writeSummary((DistributionSummary) m);
                                }
                                if (m instanceof FunctionTimer) {
                                    return writeTimer((FunctionTimer) m);
                                }
                                if (m instanceof TimeGauge) {
                                    return writeGauge(m.getId(), ((TimeGauge) m).value(getBaseTimeUnit()));
                                }
                                if (m instanceof Gauge) {
                                    return writeGauge(m.getId(), ((Gauge) m).value());
                                }
                                if (m instanceof FunctionCounter) {
                                    return writeCounter(m.getId(), ((FunctionCounter) m).count());
                                }
                                if (m instanceof Counter) {
                                    return writeCounter(m.getId(), ((Counter) m).count());
                                }
                                if (m instanceof LongTaskTimer) {
                                    return writeLongTaskTimer((LongTaskTimer) m);
                                }
                                return writeMeter(m);
                            })
                            .collect(toList());

                    String body = String.join("\n", bodyLines);

                    if (config.compressed())
                        con.setRequestProperty("Content-Encoding", "gzip");

                    try (OutputStream os = con.getOutputStream()) {
                        if (config.compressed()) {
                            try (GZIPOutputStream gz = new GZIPOutputStream(os)) {
                                gz.write(body.getBytes());
                                gz.flush();
                            }
                        } else {
                            os.write(body.getBytes());
                        }
                        os.flush();
                    }

                    int status = con.getResponseCode();

                    if (status >= 200 && status < 300) {
                        logger.info("successfully sent {} metrics to influx", batch.size());
                    } else if (status >= 400) {
                        try (InputStream in = con.getErrorStream()) {
                            logger.error("failed to send metrics: " + new BufferedReader(new InputStreamReader(in))
                                    .lines().collect(joining("\n")));
                        }
                    } else {
                        logger.error("failed to send metrics: http " + status);
                    }

                } finally {
                    quietlyCloseUrlConnection(con);
                }
            }
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Malformed InfluxDB publishing endpoint, see '" + config.prefix() + ".uri'", e);
        } catch (Throwable e) {
            logger.warn("failed to send metrics", e);
        }
    }

    private void authenticateRequest(HttpURLConnection con) {
        if (config.userName() != null && config.password() != null) {
            String encoded = Base64.getEncoder().encodeToString((config.userName() + ":" +
                    config.password()).getBytes(StandardCharsets.UTF_8));
            con.setRequestProperty("Authorization", "Basic " + encoded);
        }
    }

    private void quietlyCloseUrlConnection(@Nullable HttpURLConnection con) {
        try {
            if (con != null) {
                con.disconnect();
            }
        } catch (Exception ignore) {
        }
    }

    class Field {
        final String key;
        final double value;

        Field(String key, double value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public String toString() {
            return key + "=" + DoubleFormat.decimalOrNan(value);
        }
    }

    private String writeMeter(Meter m) {
        Stream.Builder<Field> fields = Stream.builder();

        for (Measurement measurement : m.measure()) {
            String fieldKey = measurement.getStatistic().toString()
                    .replaceAll("(.)(\\p{Upper})", "$1_$2").toLowerCase();
            fields.add(new Field(fieldKey, measurement.getValue()));
        }

        return influxLineProtocol(m.getId(), "unknown", fields.build(), clock.wallTime());
    }

    private String writeLongTaskTimer(LongTaskTimer timer) {
        Stream<Field> fields = Stream.of(
                new Field("active_tasks", timer.activeTasks()),
                new Field("duration", timer.duration(getBaseTimeUnit()))
        );

        return influxLineProtocol(timer.getId(), "long_task_timer", fields, clock.wallTime());
    }

    private String writeCounter(Meter.Id id, double count) {
        return influxLineProtocol(id, "counter", Stream.of(new Field("value", count)), clock.wallTime());
    }

    private String writeGauge(Meter.Id id, double value) {
        return influxLineProtocol(id, "gauge", Stream.of(new Field("value", value)), clock.wallTime());
    }

    private String writeTimer(FunctionTimer timer) {
        Stream<Field> fields = Stream.of(
                new Field("sum", timer.totalTime(getBaseTimeUnit())),
                new Field("count", timer.count()),
                new Field("mean", timer.mean(getBaseTimeUnit()))
        );

        return influxLineProtocol(timer.getId(), "histogram", fields, clock.wallTime());
    }

    private String writeTimer(Timer timer) {
        final HistogramSnapshot snapshot = timer.takeSnapshot(false);
        final Stream.Builder<Field> fields = Stream.builder();

        fields.add(new Field("sum", snapshot.total(getBaseTimeUnit())));
        fields.add(new Field("count", snapshot.count()));
        fields.add(new Field("mean", snapshot.mean(getBaseTimeUnit())));
        fields.add(new Field("upper", snapshot.max(getBaseTimeUnit())));

        return influxLineProtocol(timer.getId(), "histogram", fields.build(), clock.wallTime());
    }

    private String writeSummary(DistributionSummary summary) {
        final HistogramSnapshot snapshot = summary.takeSnapshot(false);
        final Stream.Builder<Field> fields = Stream.builder();

        fields.add(new Field("sum", snapshot.total()));
        fields.add(new Field("count", snapshot.count()));
        fields.add(new Field("mean", snapshot.mean()));
        fields.add(new Field("upper", snapshot.max()));

        return influxLineProtocol(summary.getId(), "histogram", fields.build(), clock.wallTime());
    }

    private String influxLineProtocol(Meter.Id id, String metricType, Stream<Field> fields, long time) {
        String tags = getConventionTags(id).stream()
                .map(t -> "," + t.getKey() + "=" + t.getValue())
                .collect(joining(""));

        return getConventionName(id)
                + tags + ",metric_type=" + metricType + " "
                + fields.map(Field::toString).collect(joining(","))
                + " " + time;
    }

    @Override
    protected final TimeUnit getBaseTimeUnit() {
        return TimeUnit.MILLISECONDS;
    }
}
