package ua.kpi.iot.probe.lwm2m;

import java.time.Duration;
import java.util.Collection;

import org.eclipse.leshan.core.node.LwM2mPath;
import org.eclipse.leshan.core.observation.Observation;
import org.eclipse.leshan.core.request.ObserveRequest;
import org.eclipse.leshan.core.response.ObserveResponse;
import org.eclipse.leshan.server.LeshanServer;
import org.eclipse.leshan.server.registration.Registration;
import org.eclipse.leshan.server.registration.RegistrationListener;
import org.eclipse.leshan.server.registration.RegistrationUpdate;
import org.springframework.beans.factory.ObjectProvider;

import ua.kpi.iot.probe.telemetry.SessionTelemetry;

/**
 * After each Register, sends one Observe per instance of the configured objects. Requests are asynchronous: the CoAP
 * dispatch thread is never blocked waiting for device responses.
 */
public class ObservationProvisioner implements RegistrationListener {

	private final ObjectProvider<LeshanServer> server;

	private final ObserveProperties properties;

	private final SessionTelemetry telemetry;

	public ObservationProvisioner(ObjectProvider<LeshanServer> server, ObserveProperties properties,
			SessionTelemetry telemetry) {
		this.server = server;
		this.properties = properties;
		this.telemetry = telemetry;
	}

	@Override
	public void registered(Registration registration, Registration previousReg,
			Collection<Observation> previousObservations) {
		if (!properties.enabled()) {
			return;
		}
		for (LwM2mPath path : registration.getAvailableInstances()) {
			if (path.isObjectInstance() && properties.objectIds().contains(path.getObjectId())) {
				observe(registration, path);
			}
		}
	}

	private void observe(Registration registration, LwM2mPath path) {
		long sentAt = System.nanoTime();
		telemetry.observeSent(registration, path);
		server.getObject()
			.send(registration, new ObserveRequest(path.getObjectId(), path.getObjectInstanceId()),
					properties.timeout().toMillis(),
					(ObserveResponse response) -> telemetry.observeCompleted(registration, path, since(sentAt),
							response.isSuccess() ? null : response.getCode() + " " + response.getErrorMessage()),
					error -> telemetry.observeCompleted(registration, path, since(sentAt), error.toString()));
	}

	private static Duration since(long nanoTime) {
		return Duration.ofNanos(System.nanoTime() - nanoTime);
	}

	@Override
	public void updated(RegistrationUpdate update, Registration updatedReg, Registration previousReg) {
	}

	@Override
	public void unregistered(Registration registration, Collection<Observation> observations, boolean expired,
			Registration newReg) {
	}

}
