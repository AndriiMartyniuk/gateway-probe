package ua.kpi.iot.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.EnumSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.leshan.client.LeshanClient;
import org.eclipse.leshan.client.LeshanClientBuilder;
import org.eclipse.leshan.client.object.Device;
import org.eclipse.leshan.client.object.Security;
import org.eclipse.leshan.client.object.Server;
import org.eclipse.leshan.client.resource.BaseInstanceEnablerFactory;
import org.eclipse.leshan.client.resource.LwM2mInstanceEnabler;
import org.eclipse.leshan.client.resource.ObjectsInitializer;
import org.eclipse.leshan.core.LwM2mId;
import org.eclipse.leshan.core.endpoint.Protocol;
import org.eclipse.leshan.core.model.StaticModel;
import org.eclipse.leshan.core.request.BindingMode;
import org.eclipse.leshan.server.LeshanServer;
import org.eclipse.leshan.server.registration.Registration;
import org.eclipse.leshan.transport.javacoap.client.coaptcp.endpoint.JavaCoapTcpClientEndpointsProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import ua.kpi.iot.probe.state.RedisBackedRegistrationStore;
import ua.kpi.iot.lwm2m.objects.ProbeSensor;

/**
 * {@code probe.state.placement=REDIS_CACHED}: registrations and observations are replicated to Redis (here a Testcontainers
 * Redis) and can be recovered by a fresh store — as by a restarted gateway instance.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "probe.state.placement=REDIS_CACHED", "probe.lwm2m.tcp.port=0",
		"probe.lwm2m.tls.enabled=false" })
class CachedRedisStatePlacementTests {

	@Autowired
	LeshanServer server;

	@Autowired
	StatefulRedisConnection<byte[], byte[]> redis;

	/** A fresh store on the same Redis, as a restarted instance would create it. */
	private RedisBackedRegistrationStore recoveredStore() {
		RedisBackedRegistrationStore store = new RedisBackedRegistrationStore(redis, "probe:", false,
				new SimpleMeterRegistry());
		store.load();
		return store;
	}

	@Test
	void registrationAndObservationsAreStoredInRedis() {
		String endpoint = "redis-device";
		ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
		LeshanClient client = startClient(endpoint, scheduler);
		try {
			await().atMost(Duration.ofSeconds(15))
				.until(() -> server.getRegistrationService().getByEndpoint(endpoint) != null);
			Registration registration = server.getRegistrationService().getByEndpoint(endpoint);
			await().atMost(Duration.ofSeconds(5))
				.until(() -> server.getObservationService().getObservations(registration).size() == 2);

			// writes reach Redis asynchronously; a fresh store loaded from Redis recovers the same session state
			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
				RedisBackedRegistrationStore recovered = recoveredStore();
				Registration stored = recovered.getRegistrationByEndpoint(endpoint);
				assertThat(stored).isNotNull();
				assertThat(stored.getId()).isEqualTo(registration.getId());
				assertThat(stored.getSocketAddress()).isEqualTo(registration.getSocketAddress());
				assertThat(recovered.getObservations(registration.getId())).hasSize(2);
			});
		}
		finally {
			client.destroy(true);
			scheduler.shutdownNow();
		}
		// Deregister removes it from Redis as well
		await().atMost(Duration.ofSeconds(5))
			.until(() -> recoveredStore().getRegistrationByEndpoint(endpoint) == null);
	}

	private LeshanClient startClient(String endpoint, ScheduledExecutorService scheduler) {
		String uri = "coap+tcp://localhost:" + server.getEndpoint(Protocol.COAP_TCP).getURI().getPort();
		ObjectsInitializer objects = new ObjectsInitializer(new StaticModel(ProbeSensor.defaultModelsWithProbeSensor()));
		objects.setInstancesForObject(LwM2mId.SECURITY, Security.noSec(uri, 123));
		objects.setInstancesForObject(LwM2mId.SERVER,
				new Server(123, 300, EnumSet.of(BindingMode.T), false, BindingMode.T));
		objects.setInstancesForObject(LwM2mId.DEVICE,
				new Device("ua.kpi.iot", "test-client", endpoint, EnumSet.of(BindingMode.T)));
		objects.setInstancesForObject(ProbeSensor.OBJECT_ID, new ProbeSensorInstance(0, scheduler, 100),
				new ProbeSensorInstance(1, scheduler, 100));
		objects.setFactoryForObject(ProbeSensor.OBJECT_ID, new BaseInstanceEnablerFactory() {
			@Override
			public LwM2mInstanceEnabler create() {
				throw new UnsupportedOperationException();
			}
		});
		LeshanClient client = new LeshanClientBuilder(endpoint).setObjects(objects.createAll())
			.setEndpointsProviders(new JavaCoapTcpClientEndpointsProvider())
			.build();
		client.start();
		return client;
	}

}
