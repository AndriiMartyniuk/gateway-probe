package ua.kpi.iot.probe.telemetry;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToDoubleFunction;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

/**
 * Micrometer meters of the probe. Names are dot-separated; Prometheus exposes them as e.g.
 * {@code probe_tls_handshake_seconds_bucket}.
 */
public class ProbeMetrics {

	private final MeterRegistry registry;

	private final Map<String, AtomicInteger> open = new ConcurrentHashMap<>();

	private final Map<String, AtomicInteger> established = new ConcurrentHashMap<>();

	public ProbeMetrics(MeterRegistry registry) {
		this.registry = registry;
		for (String transport : new String[] { "tcp", "tls" }) {
			open.put(transport, registry.gauge("probe.connections.open", Tags.of("transport", transport), new AtomicInteger()));
			established.put(transport, registry.gauge("probe.connections.established",
					Tags.of("transport", transport), new AtomicInteger()));
		}
	}

	void accepted(String transport) {
		Counter.builder("probe.connections.accepted")
			.description("TCP connections accepted")
			.tag("transport", transport)
			.register(registry)
			.increment();
		open.get(transport).incrementAndGet();
	}

	void tlsHandshake(Duration duration, boolean resumed, boolean success) {
		Timer.builder("probe.tls.handshake")
			.description("Time from TCP accept to TLS handshake completion")
			.tag("outcome", success ? "success" : "failure")
			.tag("resumed", Boolean.toString(resumed))
			.publishPercentileHistogram()
			.register(registry)
			.record(duration);
	}

	void established(String transport) {
		established.get(transport).incrementAndGet();
	}

	void closed(String transport, String reason, boolean wasEstablished, Duration lifetime) {
		Counter.builder("probe.connections.closed")
			.description("Connections closed, by reason")
			.tag("transport", transport)
			.tag("reason", reason)
			.register(registry)
			.increment();
		Timer.builder("probe.connections.lifetime")
			.description("Time from TCP accept to close")
			.tag("transport", transport)
			.register(registry)
			.record(lifetime);
		open.get(transport).decrementAndGet();
		if (wasEstablished) {
			established.get(transport).decrementAndGet();
		}
	}

	void registration(String event) {
		Counter.builder("probe.lwm2m.registrations")
			.description("LwM2M registration events: registered, updated, deregistered, expired, replaced")
			.tag("event", event)
			.register(registry)
			.increment();
	}

	void registerLatency(String transport, Duration sinceEstablished) {
		Timer.builder("probe.lwm2m.register.latency")
			.description("Time from connection established (TLS done) to LwM2M registration accepted")
			.tag("transport", transport)
			.publishPercentileHistogram()
			.register(registry)
			.record(sinceEstablished);
	}

	void observation(String event) {
		Counter.builder("probe.lwm2m.observations")
			.description("Observation events: created, cancelled, error")
			.tag("event", event)
			.register(registry)
			.increment();
	}

	void notification() {
		Counter.builder("probe.lwm2m.notifications").description("Notifications received").register(registry).increment();
	}

	void tlsHandshakeTraffic(boolean resumed, long bytesIn, long bytesOut, int flights) {
		bytes("probe.tls.handshake.bytes", "Wire bytes of a successful TLS handshake (TCP accept to completion)",
				Tags.of("direction", "in", "resumed", Boolean.toString(resumed)), bytesIn);
		bytes("probe.tls.handshake.bytes", "Wire bytes of a successful TLS handshake (TCP accept to completion)",
				Tags.of("direction", "out", "resumed", Boolean.toString(resumed)), bytesOut);
		DistributionSummary.builder("probe.tls.handshake.flights")
			.description("Flights (changes of direction) of a successful TLS handshake")
			.tag("resumed", Boolean.toString(resumed))
			.register(registry)
			.record(flights);
	}

	void connectionTraffic(String transport, long bytesIn, long bytesOut) {
		bytes("probe.connections.bytes", "Wire bytes per connection, recorded at close",
				Tags.of("transport", transport, "direction", "in"), bytesIn);
		bytes("probe.connections.bytes", "Wire bytes per connection, recorded at close",
				Tags.of("transport", transport, "direction", "out"), bytesOut);
	}

	void coapMessage(String direction, String kind, int size) {
		bytes("probe.coap.messages",
				"CoAP messages by direction and kind; size = serialised CoAP-over-TCP bytes (no TLS record overhead)",
				Tags.of("direction", direction, "kind", kind), Math.max(size, 0));
	}

	private void bytes(String name, String description, Tags tags, long value) {
		DistributionSummary.builder(name)
			.description(description)
			.baseUnit("bytes")
			.tags(tags)
			.register(registry)
			.record(value);
	}

	void observeLatency(boolean success, Duration latency) {
		Timer.builder("probe.lwm2m.observe.latency")
			.description("Time from sending Observe (after Register) to the device's response")
			.tag("outcome", success ? "success" : "failure")
			.publishPercentileHistogram()
			.register(registry)
			.record(latency);
	}

	void notificationAge(Duration age) {
		Timer.builder("probe.lwm2m.notification.age")
			.description("Data age at receipt: server receive time minus device sample time (wall clocks)")
			.publishPercentileHistogram()
			.register(registry)
			.record(age);
	}

	void notificationsMissing(long count) {
		Counter.builder("probe.lwm2m.notifications.missing")
			.description("Samples skipped in a stream's sequence numbers (lost notifications)")
			.register(registry)
			.increment(count);
	}

	void notificationDuplicate() {
		Counter.builder("probe.lwm2m.notifications.duplicate")
			.description("Notifications whose sequence number was not greater than the previous one")
			.register(registry)
			.increment();
	}

	/** Gauge backed by a supplier, e.g. queue depth or active registrations. */
	public <T> void gauge(String name, String description, Iterable<Tag> tags, T obj,
			ToDoubleFunction<T> value) {
		Gauge.builder(name, obj, value).description(description).tags(tags).register(registry);
	}

}
