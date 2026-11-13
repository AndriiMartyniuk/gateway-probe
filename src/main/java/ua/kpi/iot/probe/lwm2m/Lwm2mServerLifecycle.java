package ua.kpi.iot.probe.lwm2m;

import java.util.Collection;

import org.eclipse.leshan.core.observation.Observation;
import org.eclipse.leshan.server.LeshanServer;
import org.eclipse.leshan.server.registration.Registration;
import org.eclipse.leshan.server.registration.RegistrationListener;
import org.eclipse.leshan.server.registration.RegistrationUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Starts the Leshan server with the Spring application context and stops it on shutdown.
 */
class Lwm2mServerLifecycle implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(Lwm2mServerLifecycle.class);

	private final LeshanServer server;

	private volatile boolean running;

	Lwm2mServerLifecycle(LeshanServer server) {
		this.server = server;
		server.getRegistrationService().addListener(new LoggingRegistrationListener());
	}

	@Override
	public void start() {
		server.start();
		running = true;
		server.getEndpoints().forEach(endpoint -> log.info("LwM2M endpoint listening on {}", endpoint.getURI()));
	}

	@Override
	public void stop() {
		running = false;
		server.destroy();
		log.info("LwM2M server stopped");
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	private static final class LoggingRegistrationListener implements RegistrationListener {

		@Override
		public void registered(Registration registration, Registration previousReg,
				Collection<Observation> previousObservations) {
			log.info("Registered: endpoint={} id={} via {}", registration.getEndpoint(), registration.getId(),
					registration.getEndpointUri());
		}

		@Override
		public void updated(RegistrationUpdate update, Registration updatedReg, Registration previousReg) {
			log.debug("Updated: endpoint={}", updatedReg.getEndpoint());
		}

		@Override
		public void unregistered(Registration registration, Collection<Observation> observations, boolean expired,
				Registration newReg) {
			log.info("Unregistered: endpoint={} expired={}", registration.getEndpoint(), expired);
		}

	}

}
