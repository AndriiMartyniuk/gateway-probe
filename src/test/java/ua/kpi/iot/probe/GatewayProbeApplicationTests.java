package ua.kpi.iot.probe;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.eclipse.leshan.client.LeshanClient;
import org.eclipse.leshan.client.LeshanClientBuilder;
import org.eclipse.leshan.client.endpoint.LwM2mClientEndpointsProvider;
import org.eclipse.leshan.client.object.Device;
import org.eclipse.leshan.client.object.Security;
import org.eclipse.leshan.client.object.Server;
import org.eclipse.leshan.client.resource.ObjectsInitializer;
import org.eclipse.leshan.core.LwM2mId;
import org.eclipse.leshan.core.endpoint.Protocol;
import org.eclipse.leshan.core.request.BindingMode;
import org.eclipse.leshan.server.LeshanServer;
import org.eclipse.leshan.transport.javacoap.client.coaptcp.endpoint.JavaCoapTcpClientEndpointsProvider;
import org.eclipse.leshan.transport.javacoap.client.coaptcp.endpoint.JavaCoapsTcpClientEndpointsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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

	private static final Path CERTS = generateCerts(TLS_ENDPOINTS);

	@DynamicPropertySource
	static void lwm2mProperties(DynamicPropertyRegistry registry) {
		registry.add("probe.lwm2m.tcp.port", () -> 0);
		registry.add("probe.lwm2m.tls.port", () -> 0);
		registry.add("probe.lwm2m.tls.key-store", () -> "file:" + CERTS.resolve("server.p12"));
		registry.add("probe.lwm2m.tls.trusted-certificates", () -> "file:" + CERTS.resolve("ca.pem"));
		registry.add("probe.lwm2m.tls.x509-endpoints", () -> String.join(",", TLS_ENDPOINTS));
		// several I/O threads: packets of different connections are decoded concurrently
		registry.add("probe.lwm2m.transport.io-threads", () -> 4);
	}

	@Autowired
	LeshanServer server;

	@Autowired
	RecordingListener recorder;

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
			LwM2mClientEndpointsProvider transport) {
		ObjectsInitializer objects = new ObjectsInitializer();
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

	/** Replaces the logging listener with one that records events. */
	@TestConfiguration(proxyBeanMethods = false)
	static class RecordingEventsConfiguration {

		@Bean
		@Primary
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
		public void onEstablished(ConnectionInfo c) {
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
