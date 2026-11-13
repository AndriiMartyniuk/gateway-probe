package ua.kpi.iot.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.eclipse.leshan.client.LeshanClient;
import org.eclipse.leshan.client.LeshanClientBuilder;
import org.eclipse.leshan.client.endpoint.LwM2mClientEndpointsProvider;
import org.eclipse.leshan.client.object.Device;
import org.eclipse.leshan.client.object.Security;
import org.eclipse.leshan.client.object.Server;
import org.eclipse.leshan.client.resource.BaseInstanceEnablerFactory;
import org.eclipse.leshan.client.resource.LwM2mInstanceEnabler;
import org.eclipse.leshan.client.resource.ObjectsInitializer;
import org.eclipse.leshan.core.LwM2mId;
import org.eclipse.leshan.core.endpoint.Protocol;
import org.eclipse.leshan.core.model.ObjectLoader;
import org.eclipse.leshan.core.model.ObjectModel;
import org.eclipse.leshan.core.model.StaticModel;
import org.eclipse.leshan.core.request.BindingMode;
import org.eclipse.leshan.core.request.ReadRequest;
import org.eclipse.leshan.server.LeshanServer;
import org.eclipse.leshan.server.registration.Registration;
import org.eclipse.leshan.transport.javacoap.client.coaptcp.endpoint.JavaCoapTcpClientEndpointsProvider;
import org.eclipse.leshan.transport.javacoap.client.coaptcp.endpoint.JavaCoapsTcpClientEndpointsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import tools.jackson.databind.json.JsonMapper;
import ua.kpi.iot.lwm2m.objects.ProbeSensor;
import ua.kpi.iot.probe.telemetry.JsonlSessionEventLog;
import ua.kpi.iot.probe.telemetry.SessionEvent;
import ua.kpi.iot.probe.transport.CloseReason;
import ua.kpi.iot.probe.transport.ConnectionEventListener;

/**
 * Starts the application on random ports and drives it with real Leshan clients over CoAP over TCP and TLS.
 */
@Import({ TestcontainersConfiguration.class, GatewayProbeApplicationTests.RecordingEventsConfiguration.class })
@SpringBootTest
class GatewayProbeApplicationTests {

	private static final String TLS_DEVICE = "tls-device";

	private static final int PARALLEL_DEVICES = 20;

	private static final List<String> PARALLEL_ENDPOINTS = IntStream.range(0, PARALLEL_DEVICES)
		.mapToObj(i -> "parallel-device-" + i)
		.toList();

	private static final List<String> TLS_ENDPOINTS = Stream
		.concat(Stream.of(TLS_DEVICE), PARALLEL_ENDPOINTS.stream())
		.toList();

	/** Has a CA-signed certificate but is not listed in the security store. */
	private static final String UNLISTED_DEVICE = "unlisted-device";

	private static final Path CERTS = generateCerts(
			Stream.concat(TLS_ENDPOINTS.stream(), Stream.of(UNLISTED_DEVICE)).toList());

	@DynamicPropertySource
	static void lwm2mProperties(DynamicPropertyRegistry registry) {
		registry.add("probe.lwm2m.tcp.port", () -> 0);
		registry.add("probe.lwm2m.tls.port", () -> 0);
		registry.add("probe.lwm2m.tls.key-store", () -> "file:" + CERTS.resolve("server.p12"));
		registry.add("probe.lwm2m.tls.trusted-certificates", () -> "file:" + CERTS.resolve("ca.pem"));
		registry.add("probe.lwm2m.tls.x509-endpoints", () -> String.join(",", TLS_ENDPOINTS));
		// several I/O threads: packets of different connections are decoded concurrently
		registry.add("probe.lwm2m.transport.io-threads", () -> 4);
		registry.add("probe.event-log.enabled", () -> true);
		registry.add("probe.event-log.coap-messages", () -> true);
		registry.add("probe.event-log.directory", () -> CERTS.resolve("event-logs").toString());
	}

	@Autowired
	LeshanServer server;

	@Autowired
	RecordingListener recorder;

	@Autowired
	MeterRegistry meters;

	@Autowired
	JsonlSessionEventLog eventLog;

	@Autowired
	JsonMapper json;

	@BeforeEach
	void clearEvents() {
		recorder.events.clear();
	}

	@Test
	void registersOverCoapTcp() {
		Security security = Security.noSec(localUri(Protocol.COAP_TCP), 123);
		LeshanClient client = startClient("tcp-device", security, new JavaCoapTcpClientEndpointsProvider());
		try {
			awaitRegistered("tcp-device");
		}
		finally {
			client.destroy(true);
		}
	}

	@Test
	void registersOverCoapTlsAndReportsConnectionLifecycle() {
		LeshanClient client = startClient(TLS_DEVICE, tlsSecurity(TLS_DEVICE), new JavaCoapsTcpClientEndpointsProvider());
		try {
			awaitRegistered(TLS_DEVICE);
		}
		finally {
			client.destroy(true);
		}

		awaitEvent("closed");
		assertThat(recorder.events).extracting(Event::type)
			.containsExactly("accepted", "tls-ok", "established", "closed");
		assertThat(recorder.first("tls-ok").resumed()).isFalse();
		assertThat(recorder.first("closed").reason()).isEqualTo(CloseReason.PEER_CLOSED);
	}

	@Test
	void recordsMetricsAndSessionEventLog() throws IOException {
		double acceptedBefore = counter("probe.connections.accepted", "transport", "tls");
		long handshakesBefore = timerCount("probe.tls.handshake", "outcome", "success");
		long registerLatencyBefore = timerCount("probe.lwm2m.register.latency", "transport", "tls");

		LeshanClient client = startClient(TLS_DEVICE, tlsSecurity(TLS_DEVICE), new JavaCoapsTcpClientEndpointsProvider());
		try {
			awaitRegistered(TLS_DEVICE);
			assertThat(meters.get("probe.lwm2m.registrations.active").gauge().value()).isGreaterThanOrEqualTo(1);
			assertThat(meters.get("probe.connections.established").tag("transport", "tls").gauge().value())
				.isGreaterThanOrEqualTo(1);
		}
		finally {
			client.destroy(true);
		}
		awaitEvent("closed");

		assertThat(counter("probe.connections.accepted", "transport", "tls")).isEqualTo(acceptedBefore + 1);
		assertThat(timerCount("probe.tls.handshake", "outcome", "success")).isEqualTo(handshakesBefore + 1);
		assertThat(timerCount("probe.lwm2m.register.latency", "transport", "tls")).isEqualTo(registerLatencyBefore + 1);
		assertThat(meters.get("probe.connections.closed").tags("transport", "tls", "reason", "PEER_CLOSED").counter()
			.count()).isGreaterThanOrEqualTo(1);

		// event log: the same connection id appears on transport and LwM2M events
		String conn = recorder.first("accepted").connectionId();
		await().atMost(Duration.ofSeconds(5))
			.until(() -> sessionEvents(conn).stream().anyMatch(e -> e.event().equals("closed")));
		List<SessionEvent> session = sessionEvents(conn);
		assertThat(session).extracting(SessionEvent::event)
			.containsExactly("accepted", "tls_handshake", "established", "registered", "unregistered", "closed");
		// wire traffic of the full TLS 1.2 handshake (mutual X.509)
		SessionEvent handshake = session.get(1);
		assertThat(handshake.bytesIn()).isPositive();
		assertThat(handshake.bytesOut()).isPositive();
		assertThat(handshake.flights()).isBetween(3, 4);
		System.out.println("TRAFFIC full handshake: in=" + handshake.bytesIn() + " out=" + handshake.bytesOut()
				+ " flights=" + handshake.flights());
		// CoAP messages of the session, classified
		List<String> messages = coapMessages(conn);
		assertThat(messages).contains("in:CSM", "out:CSM", "in:REGISTER", "out:REGISTER_RESPONSE", "in:DEREGISTER",
				"out:DEREGISTER_RESPONSE");
		assertThat(messages.indexOf("in:REGISTER")).isLessThan(messages.indexOf("out:REGISTER_RESPONSE"));
		SessionEvent closedEvent = session.get(session.size() - 1);
		assertThat(closedEvent.bytesIn()).isGreaterThan(handshake.bytesIn());
		SessionEvent registered = session.get(3);
		assertThat(registered.ep()).isEqualTo(TLS_DEVICE);
		assertThat(registered.ms()).isNotNull().isPositive();
		assertThat(session.get(4).reason()).isEqualTo("deregistered");
		assertThat(session.get(5).ep()).isEqualTo(TLS_DEVICE);
	}

	private List<SessionEvent> sessionEvents(String conn) throws IOException {
		return Files.readAllLines(eventLog.file())
			.stream()
			.map(line -> json.readValue(line, SessionEvent.class))
			.filter(e -> conn.equals(e.conn()) && !e.event().equals("coap_message"))
			.toList();
	}

	/** "dir:KIND" of every CoAP message of a connection, in order. */
	private List<String> coapMessages(String conn) throws IOException {
		return Files.readAllLines(eventLog.file())
			.stream()
			.map(line -> json.readValue(line, SessionEvent.class))
			.filter(e -> conn.equals(e.conn()) && e.event().equals("coap_message"))
			.map(e -> e.dir() + ":" + e.kind())
			.toList();
	}

	private double counter(String name, String tag, String value) {
		Counter counter = meters.find(name).tag(tag, value).counter();
		return counter == null ? 0 : counter.count();
	}

	private long timerCount(String name, String tag, String value) {
		return meters.find(name).tag(tag, value).timers().stream().mapToLong(Timer::count).sum();
	}

	@Test
	void observesProbeSensorAfterRegisterAndTracksSequence() throws IOException {
		String endpoint = "sensor-device";
		double missingBefore = counter("probe.lwm2m.notifications.missing");
		ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
		ProbeSensorInstance[] sensors = { new ProbeSensorInstance(0, scheduler, 50),
				new ProbeSensorInstance(1, scheduler, 50), new ProbeSensorInstance(2, scheduler, 50) };
		LeshanClient client = startClient(endpoint, Security.noSec(localUri(Protocol.COAP_TCP), 123),
				new JavaCoapTcpClientEndpointsProvider(), sensors);
		try {
			awaitRegistered(endpoint);
			String regId = server.getRegistrationService().getByEndpoint(endpoint).getId();

			// one Observe per announced instance, answered by the device
			await().atMost(Duration.ofSeconds(10))
				.until(() -> events(e -> regId.equals(e.regId()) && e.event().equals("observe_done")).size() == 3);
			assertThat(events(e -> regId.equals(e.regId()) && e.event().equals("observe_done")))
				.allSatisfy(e -> assertThat(e.reason()).isNull())
				.extracting(SessionEvent::path)
				.containsExactlyInAnyOrder("/32700/0", "/32700/1", "/32700/2");
			assertThat(server.getObservationService().getObservations(server.getRegistrationService()
				.getByEndpoint(endpoint))).hasSize(3);

			// notifications carry sample time: data age is recorded
			await().atMost(Duration.ofSeconds(5))
				.until(() -> timerCount("probe.lwm2m.notification.age") >= 9);

			// a skipped range of sequence numbers is reported as a gap of that size
			sensors[1].skip(5);
			await().atMost(Duration.ofSeconds(5))
				.until(() -> counter("probe.lwm2m.notifications.missing") >= missingBefore + 5);
			assertThat(events(e -> regId.equals(e.regId()) && "gap".equals(e.reason()))).singleElement()
				.extracting(SessionEvent::path)
				.isEqualTo("/32700/1");
			assertThat(counter("probe.lwm2m.notifications.duplicate")).isZero();
		}
		finally {
			client.destroy(true);
			scheduler.shutdownNow();
		}

		// order within the session: registered, then observe_sent before each observe_done
		String regId = events(e -> endpoint.equals(e.ep()) && e.event().equals("registered")).getFirst().regId();
		List<String> order = events(e -> regId.equals(e.regId())).stream()
			.map(SessionEvent::event)
			.filter(t -> t.startsWith("observe") || t.equals("registered"))
			.toList();
		assertThat(order.getFirst()).isEqualTo("registered");
		assertThat(order.indexOf("observe_sent")).isLessThan(order.indexOf("observe_done"));

		// CoAP level: 3 Observe requests out, 3 responses in, then notifications
		String conn = events(e -> regId.equals(e.regId()) && e.conn() != null).getFirst().conn();
		List<String> messages = coapMessages(conn);
		assertThat(messages.stream().filter("out:OBSERVE"::equals).count()).isEqualTo(3);
		assertThat(messages.stream().filter("in:OBSERVE_RESPONSE"::equals).count()).isEqualTo(3);
		assertThat(messages).contains("in:NOTIFICATION");
		assertThat(messages.indexOf("in:OBSERVE_RESPONSE")).isLessThan(messages.indexOf("in:NOTIFICATION"));
	}

	/**
	 * Characterises Leshan (2.0.0-M18) when a TCP connection is lost without Deregister — input for experiment E3
	 * and the session-state model: the server keeps a stale registration and stale observations until the device
	 * registers again (or the lifetime expires); requests to the stale registration fail.
	 */
	@Test
	void connectionLossWithoutDeregisterLeavesStaleSessionUntilReRegister() throws Exception {
		String endpoint = "lossy-device";
		ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
		try {
			LeshanClient first = startSensorClient(endpoint, scheduler);
			awaitRegistered(endpoint);
			Registration before = server.getRegistrationService().getByEndpoint(endpoint);
			awaitObservations(before, 2);

			// connection lost: the client closes its socket without sending Deregister
			first.destroy(false);
			await().atMost(Duration.ofSeconds(5))
				.until(() -> !events(e -> endpoint.equals(e.ep()) && e.event().equals("closed")).isEmpty());

			// 1. registration and observations are still on the server, although the observations died with the
			// connection (RFC 8323)
			assertThat(server.getRegistrationService().getByEndpoint(endpoint).getId()).isEqualTo(before.getId());
			assertThat(server.getObservationService().getObservations(before)).hasSize(2);

			// 2. a request to the stale registration fails: there is no connection to the device
			assertThatThrownBy(() -> server.send(before, new ReadRequest(ProbeSensor.OBJECT_ID, 0), 2_000))
				.isInstanceOf(Exception.class);

			// 3. on reconnect, the new Register replaces the stale registration and drops its 2 observations; the
			// server observes again, so the new registration has exactly 2 (no duplicates)
			LeshanClient second = startSensorClient(endpoint, scheduler);
			try {
				await().atMost(Duration.ofSeconds(10)).until(() -> {
					Registration current = server.getRegistrationService().getByEndpoint(endpoint);
					return current != null && !current.getId().equals(before.getId());
				});
				Registration after = server.getRegistrationService().getByEndpoint(endpoint);
				awaitObservations(after, 2);
				assertThat(events(e -> before.getId().equals(e.regId()) && e.event().equals("unregistered")))
					.singleElement()
					.satisfies(e -> {
						assertThat(e.reason()).isEqualTo("replaced");
						assertThat(e.observations()).isEqualTo(2);
					});
				assertThat(events(e -> after.getId().equals(e.regId()) && e.event().equals("observe_done")))
					.hasSize(2);
			}
			finally {
				second.destroy(true);
			}
		}
		finally {
			scheduler.shutdownNow();
		}
	}

	private LeshanClient startSensorClient(String endpoint, ScheduledExecutorService scheduler) {
		return startClient(endpoint, Security.noSec(localUri(Protocol.COAP_TCP), 123),
				new JavaCoapTcpClientEndpointsProvider(), new ProbeSensorInstance(0, scheduler, 50),
				new ProbeSensorInstance(1, scheduler, 50));
	}

	private void awaitObservations(Registration registration, int count) {
		await().atMost(Duration.ofSeconds(5))
			.until(() -> server.getObservationService().getObservations(registration).size() == count);
	}

	private List<SessionEvent> events(java.util.function.Predicate<SessionEvent> filter) throws IOException {
		return Files.readAllLines(eventLog.file())
			.stream()
			.map(line -> json.readValue(line, SessionEvent.class))
			.filter(filter)
			.toList();
	}

	private double counter(String name) {
		Counter counter = meters.find(name).counter();
		return counter == null ? 0 : counter.count();
	}

	private long timerCount(String name) {
		return meters.find(name).timers().stream().mapToLong(Timer::count).sum();
	}

	@Test
	void registersUnlistedDeviceWithCaSignedCertificate() {
		LeshanClient client = startClient(UNLISTED_DEVICE, tlsSecurity(UNLISTED_DEVICE),
				new JavaCoapsTcpClientEndpointsProvider());
		try {
			awaitRegistered(UNLISTED_DEVICE);
		}
		finally {
			client.destroy(true);
		}
	}

	@Test
	void rejectsCertificateWhoseCommonNameIsNotTheEndpoint() throws Exception {
		// "impostor" presents the (valid, CA-signed) certificate of unlisted-device
		LeshanClient client = startClient("impostor", tlsSecurity(UNLISTED_DEVICE),
				new JavaCoapsTcpClientEndpointsProvider());
		try {
			// the TLS handshake succeeds (the certificate is trusted), but Register is refused
			awaitEvent("tls-ok");
			Thread.sleep(2_000);
			assertThat(server.getRegistrationService().getByEndpoint("impostor")).isNull();
		}
		finally {
			client.destroy(false);
		}
	}

	@Test
	void reportsFailedTlsHandshake() throws IOException {
		try (Socket socket = new Socket("localhost", port(Protocol.COAPS_TCP))) {
			OutputStream out = socket.getOutputStream();
			out.write("not a TLS client hello\r\n".getBytes(StandardCharsets.US_ASCII));
			out.flush();
			awaitEvent("closed");
		}
		assertThat(recorder.events).extracting(Event::type).containsExactly("accepted", "tls-failed", "closed");
		assertThat(recorder.first("closed").reason()).isEqualTo(CloseReason.TLS_HANDSHAKE_FAILED);
	}

	@Test
	void registersManyDevicesInParallelOverTls() {
		List<LeshanClient> clients = new CopyOnWriteArrayList<>();
		try {
			PARALLEL_ENDPOINTS.parallelStream()
				.forEach(ep -> clients.add(startClient(ep, tlsSecurity(ep), new JavaCoapsTcpClientEndpointsProvider())));
			PARALLEL_ENDPOINTS.forEach(this::awaitRegistered);
		}
		finally {
			clients.forEach(client -> client.destroy(true));
		}
	}

	private Security tlsSecurity(String endpoint) {
		try {
			return Security.x509(localUri(Protocol.COAPS_TCP), 123, Files.readAllBytes(CERTS.resolve(endpoint + ".der")),
					Files.readAllBytes(CERTS.resolve(endpoint + ".key.der")),
					Files.readAllBytes(CERTS.resolve("server.der")));
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private int port(Protocol protocol) {
		return server.getEndpoint(protocol).getURI().getPort();
	}

	/** The server binds to the wildcard address; clients connect through localhost on the bound port. */
	private String localUri(Protocol protocol) {
		return protocol.getUriScheme() + "://localhost:" + port(protocol);
	}

	private void awaitRegistered(String endpoint) {
		await().atMost(Duration.ofSeconds(15))
			.untilAsserted(() -> assertThat(server.getRegistrationService().getByEndpoint(endpoint)).isNotNull());
	}

	private void awaitEvent(String type) {
		await().atMost(Duration.ofSeconds(5))
			.until(() -> recorder.events.stream().anyMatch(e -> e.type().equals(type)));
	}

	private static LeshanClient startClient(String endpoint, Security security,
			LwM2mClientEndpointsProvider transport, ProbeSensorInstance... sensors) {
		List<ObjectModel> models = new ArrayList<>(ObjectLoader.loadAllDefault());
		models.addAll(ProbeSensor.models());
		ObjectsInitializer objects = new ObjectsInitializer(new StaticModel(models));
		if (sensors.length > 0) {
			objects.setInstancesForObject(ProbeSensor.OBJECT_ID, sensors);
			// Leshan needs a factory for multi-instance objects (server-side Create); tests never create instances
			objects.setFactoryForObject(ProbeSensor.OBJECT_ID, new BaseInstanceEnablerFactory() {
				@Override
				public LwM2mInstanceEnabler create() {
					throw new UnsupportedOperationException("Create of Probe Sensor instances is not supported");
				}
			});
		}
		objects.setInstancesForObject(LwM2mId.SECURITY, security);
		objects.setInstancesForObject(LwM2mId.SERVER,
				new Server(123, 300, EnumSet.of(BindingMode.T), false, BindingMode.T));
		objects.setInstancesForObject(LwM2mId.DEVICE,
				new Device("ua.kpi.iot", "test-client", endpoint, EnumSet.of(BindingMode.T)));
		LeshanClient client = new LeshanClientBuilder(endpoint).setObjects(objects.createAll())
			.setEndpointsProviders(transport)
			.build();
		client.start();
		return client;
	}

	private static Path generateCerts(List<String> clientEndpoints) {
		try {
			Path dir = Files.createTempDirectory("gateway-probe-certs");
			List<String> command = new ArrayList<>(List.of("bash", "scripts/generate-dev-certs.sh", dir.toString()));
			command.addAll(clientEndpoints);
			Process process = new ProcessBuilder(command).redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.start();
			if (process.waitFor() != 0) {
				throw new IllegalStateException("scripts/generate-dev-certs.sh failed");
			}
			return dir;
		}
		catch (IOException | InterruptedException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Adds a listener that records transport events next to the real ones. */
	@TestConfiguration(proxyBeanMethods = false)
	static class RecordingEventsConfiguration {

		@Bean
		RecordingListener recordingListener() {
			return new RecordingListener();
		}

	}

	record Event(String type, String connectionId, boolean tls, boolean resumed, CloseReason reason) {
	}

	static class RecordingListener implements ConnectionEventListener {

		final List<Event> events = new CopyOnWriteArrayList<>();

		@Override
		public void onAccepted(ConnectionInfo c) {
			events.add(new Event("accepted", c.id(), c.tls(), false, null));
		}

		@Override
		public void onTlsHandshake(ConnectionInfo c, Duration duration, boolean resumed, Throwable failure) {
			events.add(new Event(failure == null ? "tls-ok" : "tls-failed", c.id(), c.tls(), resumed, null));
		}

		@Override
		public void onEstablished(ConnectionInfo c, Duration sinceAccept) {
			events.add(new Event("established", c.id(), c.tls(), false, null));
		}

		@Override
		public void onClosed(ConnectionInfo c, CloseReason reason, Duration lifetime) {
			events.add(new Event("closed", c.id(), c.tls(), false, reason));
		}

		Event first(String type) {
			return events.stream().filter(e -> e.type().equals(type)).findFirst().orElseThrow();
		}

	}

}
