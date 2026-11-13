package ua.kpi.iot.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class GatewayProbeApplicationTests {

	private static final String TLS_DEVICE = "tls-device";

	private static final Path CERTS = generateCerts();

	@DynamicPropertySource
	static void lwm2mProperties(DynamicPropertyRegistry registry) {
		registry.add("probe.lwm2m.tcp.port", () -> 0);
		registry.add("probe.lwm2m.tls.port", () -> 0);
		registry.add("probe.lwm2m.tls.key-store", () -> "file:" + CERTS.resolve("server.p12"));
		registry.add("probe.lwm2m.tls.trusted-certificates", () -> "file:" + CERTS.resolve("ca.pem"));
		registry.add("probe.lwm2m.tls.x509-endpoints", () -> TLS_DEVICE);
	}

	@Autowired
	LeshanServer server;

	@Test
	void registersOverCoapTcp() {
		String uri = localUri(Protocol.COAP_TCP);
		assertRegisters("tcp-device", Security.noSec(uri, 123), new JavaCoapTcpClientEndpointsProvider());
	}

	@Test
	void registersOverCoapTls() throws IOException {
		String uri = localUri(Protocol.COAPS_TCP);
		Security security = Security.x509(uri, 123, Files.readAllBytes(CERTS.resolve(TLS_DEVICE + ".der")),
				Files.readAllBytes(CERTS.resolve(TLS_DEVICE + ".key.der")),
				Files.readAllBytes(CERTS.resolve("server.der")));
		assertRegisters(TLS_DEVICE, security, new JavaCoapsTcpClientEndpointsProvider());
	}

	/** The server binds to the wildcard address; clients connect through localhost on the bound port. */
	private String localUri(Protocol protocol) {
		return protocol.getUriScheme() + "://localhost:" + server.getEndpoint(protocol).getURI().getPort();
	}

	private void assertRegisters(String endpoint, Security security, LwM2mClientEndpointsProvider transport) {
		ObjectsInitializer objects = new ObjectsInitializer();
		objects.setInstancesForObject(LwM2mId.SECURITY, security);
		objects.setInstancesForObject(LwM2mId.SERVER,
				new Server(123, 300, EnumSet.of(BindingMode.T), false, BindingMode.T));
		objects.setInstancesForObject(LwM2mId.DEVICE,
				new Device("ua.kpi.iot", "test-client", endpoint, EnumSet.of(BindingMode.T)));

		LeshanClient client = new LeshanClientBuilder(endpoint).setObjects(objects.createAll())
			.setEndpointsProviders(transport)
			.build();
		try {
			client.start();
			await().atMost(Duration.ofSeconds(10))
				.untilAsserted(() -> assertThat(server.getRegistrationService().getByEndpoint(endpoint)).isNotNull());
		}
		finally {
			client.destroy(true);
		}
	}

	private static Path generateCerts() {
		try {
			Path dir = Files.createTempDirectory("gateway-probe-certs");
			Process process = new ProcessBuilder("bash", "scripts/generate-dev-certs.sh", dir.toString(), TLS_DEVICE)
				.inheritIO()
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

}
