package ua.kpi.iot.probe.lwm2m;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.leshan.server.LeshanServer;
import org.eclipse.leshan.server.LeshanServerBuilder;
import org.eclipse.leshan.server.endpoint.LwM2mServerEndpointsProvider;
import org.eclipse.leshan.server.model.VersionedModelProvider;
import org.eclipse.leshan.server.redis.RedisRegistrationStore;
import org.eclipse.leshan.server.security.DefaultAuthorizer;
import org.eclipse.leshan.server.observation.ObservationListener;
import org.eclipse.leshan.server.registration.RegistrationListener;
import org.eclipse.leshan.servers.security.EditableSecurityStore;
import org.eclipse.leshan.servers.security.InMemorySecurityStore;
import org.eclipse.leshan.servers.security.NonUniqueSecurityInfoException;
import org.eclipse.leshan.servers.security.SecurityInfo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import ua.kpi.iot.lwm2m.objects.ProbeSensor;
import ua.kpi.iot.probe.state.RedisBackedRegistrationStore;
import ua.kpi.iot.probe.telemetry.SessionTelemetry;
import ua.kpi.iot.probe.transport.CoapTcpServerEndpointsProvider;
import ua.kpi.iot.probe.transport.CoapsTcpServerEndpointsProvider;
import ua.kpi.iot.probe.transport.CompositeConnectionEventListener;
import ua.kpi.iot.probe.transport.ConnectionEventListener;
import ua.kpi.iot.probe.transport.LoggingConnectionEventListener;
import ua.kpi.iot.probe.transport.TransportSettings;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ Lwm2mServerProperties.class, ObserveProperties.class, StateProperties.class })
class Lwm2mServerConfiguration {

	@Bean
	EditableSecurityStore lwm2mSecurityStore(Lwm2mServerProperties properties) throws NonUniqueSecurityInfoException {
		InMemorySecurityStore store = new InMemorySecurityStore();
		for (String endpoint : properties.tls().x509Endpoints()) {
			store.add(SecurityInfo.newX509CertInfo(endpoint));
		}
		return store;
	}

	@Bean
	ConnectionEventListener connectionEventListener() {
		return new LoggingConnectionEventListener();
	}

	/** {@code REDIS}: Jedis pool for Leshan's {@code RedisRegistrationStore}, on {@code spring.data.redis.*}. */
	@Bean(destroyMethod = "close")
	@ConditionalOnProperty(name = "probe.state.placement", havingValue = "REDIS")
	JedisPool lwm2mStateJedisPool(DataRedisConnectionDetails redis, StateProperties state, MeterRegistry meters) {
		DataRedisConnectionDetails.Standalone standalone = standalone(redis);
		JedisPoolConfig config = new JedisPoolConfig();
		config.setMaxTotal(state.redis().poolSize());
		config.setMaxIdle(state.redis().poolSize());
		JedisPool pool = new JedisPool(config, standalone.getHost(), standalone.getPort(), 2_000, redis.getUsername(),
				redis.getPassword(), standalone.getDatabase());
		Gauge.builder("probe.state.redis.pool.active", pool, JedisPool::getNumActive)
			.description("Jedis connections in use")
			.register(meters);
		Gauge.builder("probe.state.redis.pool.waiters", pool, JedisPool::getNumWaiters)
			.description("Threads waiting for a Jedis connection")
			.register(meters);
		return pool;
	}

	private static DataRedisConnectionDetails.Standalone standalone(DataRedisConnectionDetails redis) {
		DataRedisConnectionDetails.Standalone standalone = redis.getStandalone();
		if (standalone == null) {
			throw new IllegalStateException("Redis state placement needs a standalone Redis (spring.data.redis.host)");
		}
		return standalone;
	}

	/** {@code REDIS_CACHED}: Lettuce client for the asynchronous replica, on {@code spring.data.redis.*}. */
	@Bean(destroyMethod = "shutdown")
	@ConditionalOnProperty(name = "probe.state.placement", havingValue = "REDIS_CACHED")
	RedisClient lwm2mStateRedisClient(DataRedisConnectionDetails redis) {
		DataRedisConnectionDetails.Standalone standalone = standalone(redis);
		RedisURI.Builder uri = RedisURI.builder()
			.withHost(standalone.getHost())
			.withPort(standalone.getPort())
			.withDatabase(standalone.getDatabase());
		if (redis.getPassword() != null) {
			if (redis.getUsername() != null) {
				uri.withAuthentication(redis.getUsername(), redis.getPassword().toCharArray());
			}
			else {
				uri.withPassword(redis.getPassword().toCharArray());
			}
		}
		return RedisClient.create(uri.build());
	}

	@Bean(destroyMethod = "close")
	@ConditionalOnProperty(name = "probe.state.placement", havingValue = "REDIS_CACHED")
	StatefulRedisConnection<byte[], byte[]> lwm2mStateRedisConnection(RedisClient client) {
		return client.connect(ByteArrayCodec.INSTANCE);
	}

	@Bean
	LeshanServer leshanServer(Lwm2mServerProperties properties, StateProperties state,
			ObjectProvider<JedisPool> jedisPool, ObjectProvider<StatefulRedisConnection<byte[], byte[]>> redisConnection,
			MeterRegistry meters,
			EditableSecurityStore securityStore,
			List<ConnectionEventListener> connectionListeners, List<RegistrationListener> registrationListeners,
			List<ObservationListener> observationListeners) throws IOException, GeneralSecurityException {
		ConnectionEventListener connectionEvents = new CompositeConnectionEventListener(connectionListeners);
		LeshanServerBuilder builder = new LeshanServerBuilder();
		builder.setObjectModelProvider(new VersionedModelProvider(ProbeSensor.defaultModelsWithProbeSensor()));
		builder.setSecurityStore(securityStore);
		switch (state.placement()) {
			case IN_MEMORY -> {
			}
			case REDIS -> builder.setRegistrationStore(new RedisRegistrationStore(jedisPool.getObject()));
			case REDIS_CACHED -> builder.setRegistrationStore(new RedisBackedRegistrationStore(
					redisConnection.getObject(), state.redis().keyPrefix(), state.redis().loadOnStart(), meters));
		}
		if (properties.tls().acceptCaSignedDevices()) {
			builder.setAuthorizer(new DefaultAuthorizer(securityStore, new CaSignedDeviceSecurityChecker()));
		}

		// Replace Leshan's default UDP (Californium) endpoints with CoAP over TCP / TLS on our own Netty transport.
		List<LwM2mServerEndpointsProvider> endpoints = new ArrayList<>();
		TransportSettings transport = properties.transport().toSettings();

		Lwm2mServerProperties.Tcp tcp = properties.tcp();
		if (tcp.enabled()) {
			endpoints.add(new CoapTcpServerEndpointsProvider(new InetSocketAddress(tcp.host(), tcp.port()), transport,
					connectionEvents));
		}

		Lwm2mServerProperties.Tls tls = properties.tls();
		if (tls.enabled()) {
			KeyStore keyStore = loadKeyStore(tls.keyStore(), tls.keyStorePassword());
			char[] password = tls.keyStorePassword().toCharArray();
			builder.setPrivateKey((PrivateKey) keyStore.getKey(tls.keyAlias(), password));
			Certificate[] chain = keyStore.getCertificateChain(tls.keyAlias());
			if (chain == null) {
				throw new IllegalStateException("No certificate chain for alias '" + tls.keyAlias() + "'");
			}
			builder.setCertificateChain(Arrays.copyOf(chain, chain.length, X509Certificate[].class));
			builder.setTrustedCertificates(loadCertificates(tls.trustedCertificates()));
			endpoints.add(new CoapsTcpServerEndpointsProvider(new InetSocketAddress(tls.host(), tls.port()), transport,
					connectionEvents));
		}

		if (endpoints.isEmpty()) {
			throw new IllegalStateException("Enable at least one of probe.lwm2m.tcp / probe.lwm2m.tls");
		}
		builder.setEndpointsProviders(endpoints);
		LeshanServer server = builder.build();
		registrationListeners.forEach(server.getRegistrationService()::addListener);
		observationListeners.forEach(server.getObservationService()::addListener);
		return server;
	}

	/** Runs after {@code SessionTelemetry} so "registered" is recorded before "observe_sent". */
	@Bean
	@Order(Ordered.LOWEST_PRECEDENCE)
	ObservationProvisioner observationProvisioner(ObjectProvider<LeshanServer> server, ObserveProperties properties,
			SessionTelemetry telemetry) {
		return new ObservationProvisioner(server, properties, telemetry);
	}

	@Bean
	Lwm2mServerLifecycle lwm2mServerLifecycle(LeshanServer server) {
		return new Lwm2mServerLifecycle(server);
	}

	private static KeyStore loadKeyStore(Resource resource, String password)
			throws IOException, GeneralSecurityException {
		requirePresent(resource, "probe.lwm2m.tls.key-store");
		KeyStore keyStore = KeyStore.getInstance("PKCS12");
		try (InputStream in = resource.getInputStream()) {
			keyStore.load(in, password.toCharArray());
		}
		return keyStore;
	}

	private static X509Certificate[] loadCertificates(Resource resource) throws IOException, GeneralSecurityException {
		requirePresent(resource, "probe.lwm2m.tls.trusted-certificates");
		try (InputStream in = resource.getInputStream()) {
			return CertificateFactory.getInstance("X.509")
				.generateCertificates(in)
				.toArray(X509Certificate[]::new);
		}
	}

	private static void requirePresent(Resource resource, String property) {
		if (resource == null || !resource.exists()) {
			throw new IllegalStateException(property + " is not set or does not exist (" + resource
					+ "). Run scripts/generate-dev-certs.sh or disable TLS with probe.lwm2m.tls.enabled=false");
		}
	}

}
