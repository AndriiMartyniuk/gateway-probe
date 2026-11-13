package ua.kpi.iot.probe;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.eclipse.leshan.client.resource.BaseInstanceEnabler;
import org.eclipse.leshan.client.servers.LwM2mServer;
import org.eclipse.leshan.core.node.LwM2mResource;
import org.eclipse.leshan.core.response.ReadResponse;
import org.eclipse.leshan.core.response.WriteResponse;

import ua.kpi.iot.lwm2m.objects.ProbeSensor;

/**
 * Device side of the Probe Sensor object for tests: produces a sample every {@code period} ms and notifies observers.
 */
class ProbeSensorInstance extends BaseInstanceEnabler {

	private final ScheduledExecutorService scheduler;

	private long sequence;

	private long sampleTimeMillis = System.currentTimeMillis();

	private double value;

	private long periodMs;

	private long payloadSize;

	private ScheduledFuture<?> task;

	ProbeSensorInstance(int id, ScheduledExecutorService scheduler, long periodMs) {
		super(id);
		this.scheduler = scheduler;
		this.periodMs = periodMs;
		schedule();
	}

	/** Advances the sequence without notifying, to simulate lost notifications. */
	synchronized void skip(long samples) {
		sequence += samples;
	}

	synchronized void stop() {
		if (task != null) {
			task.cancel(false);
		}
	}

	private synchronized void schedule() {
		stop();
		if (periodMs > 0) {
			task = scheduler.scheduleAtFixedRate(this::sample, periodMs, periodMs, TimeUnit.MILLISECONDS);
		}
	}

	private void sample() {
		synchronized (this) {
			sequence++;
			sampleTimeMillis = System.currentTimeMillis();
			value = ThreadLocalRandom.current().nextDouble(0, 100);
		}
		fireResourcesChange(getResourcePaths(ProbeSensor.SEQUENCE_NUMBER, ProbeSensor.SAMPLE_TIME, ProbeSensor.VALUE));
	}

	@Override
	public synchronized ReadResponse read(LwM2mServer server, int resourceId) {
		return switch (resourceId) {
			case ProbeSensor.SEQUENCE_NUMBER -> ReadResponse.success(resourceId, sequence);
			case ProbeSensor.SAMPLE_TIME -> ReadResponse.success(resourceId, sampleTimeMillis);
			case ProbeSensor.VALUE -> ReadResponse.success(resourceId, value);
			case ProbeSensor.PAYLOAD -> ReadResponse.success(resourceId, new byte[(int) payloadSize]);
			case ProbeSensor.SAMPLE_PERIOD -> ReadResponse.success(resourceId, periodMs);
			case ProbeSensor.PAYLOAD_SIZE -> ReadResponse.success(resourceId, payloadSize);
			default -> super.read(server, resourceId);
		};
	}

	@Override
	public synchronized WriteResponse write(LwM2mServer server, boolean replace, int resourceId,
			LwM2mResource resource) {
		switch (resourceId) {
			case ProbeSensor.SAMPLE_PERIOD -> {
				periodMs = (Long) resource.getValue();
				schedule();
			}
			case ProbeSensor.PAYLOAD_SIZE -> payloadSize = (Long) resource.getValue();
			default -> {
				return super.write(server, replace, resourceId, resource);
			}
		}
		fireResourceChange(resourceId);
		return WriteResponse.success();
	}

}
