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
import org.eclipse.leshan.server.observation.ObservationListener;
import org.eclipse.leshan.server.registration.RegistrationListener;
import org.eclipse.leshan.servers.security.EditableSecurityStore;
import org.eclipse.leshan.servers.security.InMemorySecurityStore;
import org.eclipse.leshan.servers.security.NonUniqueSecurityInfoException;
import org.eclipse.leshan.servers.security.SecurityInfo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;

import ua.kpi.iot.lwm2m.objects.ProbeSensor;
import ua.kpi.iot.probe.telemetry.SessionTelemetry;
import ua.kpi.iot.probe.transport.CoapTcpServerEndpointsProvider;
import ua.kpi.iot.probe.transport.CoapsTcpServerEndpointsProvider;
import ua.kpi.iot.probe.transport.CompositeConnectionEventListener;
import ua.kpi.iot.probe.transport.ConnectionEventListener;
import ua.kpi.iot.probe.transport.LoggingConnectionEventListener;
import ua.kpi.iot.probe.transport.TransportSettings;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ Lwm2mServerProperties.class, ObserveProperties.class })
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

	@Bean
	LeshanServer leshanServer(Lwm2mServerProperties properties, EditableSecurityStore securityStore,
			List<ConnectionEventListener> connectionListeners, List<RegistrationListener> registrationListeners,
			List<ObservationListener> observationListeners) throws IOException, GeneralSecurityException {
		ConnectionEventListener connectionEvents = new CompositeConnectionEventListener(connectionListeners);
		LeshanServerBuilder builder = new LeshanServerBuilder();
		builder.setObjectModelProvider(new VersionedModelProvider(ProbeSensor.defaultModelsWithProbeSensor()));
		builder.setSecurityStore(securityStore);

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
