package ua.kpi.iot.probe.telemetry;

import java.time.Duration;
import java.util.List;
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
import ua.kpi.iot.probe.transport.CloseReason;
import ua.kpi.iot.probe.transport.CoapMessageKind;

/**
 * Micrometer meters of the probe. Names are dot-separated; Prometheus exposes them as e.g.
 * {@code probe_tls_handshake_seconds_bucket}.
 *
 * <p>
 * Every meter is registered with all its known tag combinations at construction (value 0). A meter created lazily
 * at its first event appears in Prometheus already at its burst value; with no earlier sample, {@code rate()} and
 * {@code increase()} would show the burst as 0 — and bursts (reconnect waves) are what the experiments look at.
 */
public class ProbeMetrics {

	private static final List<String> TRANSPORTS = List.of("tcp", "tls");

	private static final List<String> BOOLEANS = List.of("true", "false");

	private static final List<String> DIRECTIONS = List.of("in", "out");

	private static final List<String> REGISTRATION_EVENTS = List.of("registered", "updated", "deregistered", "expired",
			"replaced");

	private static final List<String> OBSERVATION_EVENTS = List.of("created", "cancelled", "error");

	private final MeterRegistry registry;

	private final Map<String, AtomicInteger> open = new ConcurrentHashMap<>();

	private final Map<String, AtomicInteger> established = new ConcurrentHashMap<>();

	public ProbeMetrics(MeterRegistry registry) {
		this.registry = registry;
		for (String transport : TRANSPORTS) {
			open.put(transport,
					registry.gauge("probe.connections.open", Tags.of("transport", transport), new AtomicInteger()));
			established.put(transport, registry.gauge("probe.connections.established",
					Tags.of("transport", transport), new AtomicInteger()));
			acceptedCounter(transport);
			lifetimeTimer(transport);
			registerLatencyTimer(transport);
			for (CloseReason reason : CloseReason.values()) {
				closedCounter(transport, reason.name());
			}
			for (String direction : DIRECTIONS) {
				connectionBytes(transport, direction);
			}
		}
		for (String resumed : BOOLEANS) {
			tlsHandshakeTimer("success", resumed);
			tlsHandshakeTimer("failure", resumed);
			flightsSummary(resumed);
			for (String direction : DIRECTIONS) {
				handshakeBytes(direction, resumed);
			}
		}
		REGISTRATION_EVENTS.forEach(this::registrationCounter);
		OBSERVATION_EVENTS.forEach(this::observationCounter);
		observeLatencyTimer("success");
		observeLatencyTimer("failure");
		notificationsCounter();
		missingCounter();
		duplicateCounter();
		notificationAgeTimer();
		for (CoapMessageKind kind : CoapMessageKind.values()) {
			for (String direction : DIRECTIONS) {
				coapMessages(direction, kind.name());
			}
		}
	}

	// ---- recording ---------------------------------------------------------------------------------------------

	void accepted(String transport) {
		acceptedCounter(transport).increment();
		open.get(transport).incrementAndGet();
	}

	void tlsHandshake(Duration duration, boolean resumed, boolean success) {
		tlsHandshakeTimer(success ? "success" : "failure", Boolean.toString(resumed)).record(duration);
	}

	void established(String transport) {
		established.get(transport).incrementAndGet();
	}

	void closed(String transport, String reason, boolean wasEstablished, Duration lifetime) {
		closedCounter(transport, reason).increment();
		lifetimeTimer(transport).record(lifetime);
		open.get(transport).decrementAndGet();
		if (wasEstablished) {
			established.get(transport).decrementAndGet();
		}
	}

	void registration(String event) {
		registrationCounter(event).increment();
	}

	void registerLatency(String transport, Duration sinceEstablished) {
		registerLatencyTimer(transport).record(sinceEstablished);
	}

	void observation(String event) {
		observationCounter(event).increment();
	}

	void notification() {
		notificationsCounter().increment();
	}

	void tlsHandshakeTraffic(boolean resumed, long bytesIn, long bytesOut, int flights) {
		String r = Boolean.toString(resumed);
		handshakeBytes("in", r).record(bytesIn);
		handshakeBytes("out", r).record(bytesOut);
		flightsSummary(r).record(flights);
	}

	void connectionTraffic(String transport, long bytesIn, long bytesOut) {
		connectionBytes(transport, "in").record(bytesIn);
		connectionBytes(transport, "out").record(bytesOut);
	}

	void coapMessage(String direction, String kind, int size) {
		coapMessages(direction, kind).record(Math.max(size, 0));
	}

	void observeLatency(boolean success, Duration latency) {
		observeLatencyTimer(success ? "success" : "failure").record(latency);
	}

	void notificationAge(Duration age) {
		notificationAgeTimer().record(age);
	}

	void notificationsMissing(long count) {
		missingCounter().increment(count);
	}

	void notificationDuplicate() {
		duplicateCounter().increment();
	}

	/** Gauge backed by a supplier, e.g. queue depth or active registrations. */
	public <T> void gauge(String name, String description, Iterable<Tag> tags, T obj, ToDoubleFunction<T> value) {
		Gauge.builder(name, obj, value).description(description).tags(tags).register(registry);
	}

	// ---- meter definitions (register returns the existing meter for known name + tags) ------------------------

	private Counter acceptedCounter(String transport) {
		return Counter.builder("probe.connections.accepted")
			.description("TCP connections accepted")
			.tag("transport", transport)
			.register(registry);
	}

	private Timer tlsHandshakeTimer(String outcome, String resumed) {
		return Timer.builder("probe.tls.handshake")
			.description("Time from TCP accept to TLS handshake completion")
			.tag("outcome", outcome)
			.tag("resumed", resumed)
			.publishPercentileHistogram()
			.register(registry);
	}

	private Counter closedCounter(String transport, String reason) {
		return Counter.builder("probe.connections.closed")
			.description("Connections closed, by reason")
			.tag("transport", transport)
			.tag("reason", reason)
			.register(registry);
	}

	private Timer lifetimeTimer(String transport) {
		return Timer.builder("probe.connections.lifetime")
			.description("Time from TCP accept to close")
			.tag("transport", transport)
			.register(registry);
	}

	private Counter registrationCounter(String event) {
		return Counter.builder("probe.lwm2m.registrations")
			.description("LwM2M registration events: registered, updated, deregistered, expired, replaced")
			.tag("event", event)
			.register(registry);
	}

	private Timer registerLatencyTimer(String transport) {
		return Timer.builder("probe.lwm2m.register.latency")
			.description("Time from connection established (TLS done) to LwM2M registration accepted")
			.tag("transport", transport)
			.publishPercentileHistogram()
			.register(registry);
	}

	private Counter observationCounter(String event) {
		return Counter.builder("probe.lwm2m.observations")
			.description("Observation events: created, cancelled, error")
			.tag("event", event)
			.register(registry);
	}

	private Counter notificationsCounter() {
		return Counter.builder("probe.lwm2m.notifications").description("Notifications received").register(registry);
	}

	private DistributionSummary handshakeBytes(String direction, String resumed) {
		return bytes("probe.tls.handshake.bytes", "Wire bytes of a successful TLS handshake (TCP accept to completion)",
				Tags.of("direction", direction, "resumed", resumed));
	}

	private DistributionSummary flightsSummary(String resumed) {
		return DistributionSummary.builder("probe.tls.handshake.flights")
			.description("Flights (changes of direction) of a successful TLS handshake")
			.tag("resumed", resumed)
			.register(registry);
	}

	private DistributionSummary connectionBytes(String transport, String direction) {
		return bytes("probe.connections.bytes", "Wire bytes per connection, recorded at close",
				Tags.of("transport", transport, "direction", direction));
	}

	private DistributionSummary coapMessages(String direction, String kind) {
		return bytes("probe.coap.messages",
				"CoAP messages by direction and kind; size = serialised CoAP-over-TCP bytes (no TLS record overhead)",
				Tags.of("direction", direction, "kind", kind));
	}

	private DistributionSummary bytes(String name, String description, Tags tags) {
		return DistributionSummary.builder(name).description(description).baseUnit("bytes").tags(tags).register(registry);
	}

	private Timer observeLatencyTimer(String outcome) {
		return Timer.builder("probe.lwm2m.observe.latency")
			.description("Time from sending Observe (after Register) to the device's response")
			.tag("outcome", outcome)
			.publishPercentileHistogram()
			.register(registry);
	}

	private Timer notificationAgeTimer() {
		return Timer.builder("probe.lwm2m.notification.age")
			.description("Data age at receipt: server receive time minus device sample time (wall clocks)")
			.publishPercentileHistogram()
			.register(registry);
	}

	private Counter missingCounter() {
		return Counter.builder("probe.lwm2m.notifications.missing")
			.description("Samples skipped in a stream's sequence numbers (lost notifications)")
			.register(registry);
	}

	private Counter duplicateCounter() {
		return Counter.builder("probe.lwm2m.notifications.duplicate")
			.description("Notifications whose sequence number was not greater than the previous one")
			.register(registry);
	}

}
