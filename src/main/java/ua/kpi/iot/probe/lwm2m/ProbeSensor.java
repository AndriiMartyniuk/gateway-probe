package ua.kpi.iot.probe.lwm2m;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import org.eclipse.leshan.core.model.InvalidDDFFileException;
import org.eclipse.leshan.core.model.InvalidModelException;
import org.eclipse.leshan.core.model.ObjectLoader;
import org.eclipse.leshan.core.model.ObjectModel;
import org.eclipse.leshan.core.node.LwM2mNode;
import org.eclipse.leshan.core.node.LwM2mObjectInstance;
import org.eclipse.leshan.core.node.LwM2mResource;

/**
 * Probe Sensor object (ID 32700, {@code models/32700.xml}): a private research object whose samples carry a sequence
 * number and a device-side sample time.
 */
public final class ProbeSensor {

	public static final int OBJECT_ID = 32700;

	public static final int SEQUENCE_NUMBER = 0;

	public static final int SAMPLE_TIME = 1;

	public static final int VALUE = 2;

	public static final int PAYLOAD = 3;

	public static final int SAMPLE_PERIOD = 4;

	public static final int PAYLOAD_SIZE = 5;

	private static final String MODEL_PATH = "/models/";

	private static final String MODEL_FILE = OBJECT_ID + ".xml";

	private ProbeSensor() {
	}

	/** Loads the object model from the classpath. */
	public static List<ObjectModel> models() {
		try {
			return ObjectLoader.loadDdfResources(MODEL_PATH, new String[] { MODEL_FILE });
		}
		catch (IOException | InvalidModelException | InvalidDDFFileException ex) {
			throw new IllegalStateException("Unable to load " + MODEL_PATH + MODEL_FILE, ex);
		}
	}

	/** Sequence number and sample time (device clock, ms resolution) extracted from a notification. */
	public record Sample(long sequence, Instant sampleTime) {

		/**
		 * @return the sample, or {@code null} if the node is not a Probe Sensor instance with both resources
		 */
		public static Sample from(LwM2mNode node) {
			if (!(node instanceof LwM2mObjectInstance instance)) {
				return null;
			}
			LwM2mResource sequence = instance.getResource(SEQUENCE_NUMBER);
			LwM2mResource time = instance.getResource(SAMPLE_TIME);
			if (sequence == null || time == null || !(sequence.getValue() instanceof Number seq)
					|| !(time.getValue() instanceof Number epochMillis)) {
				return null;
			}
			return new Sample(seq.longValue(), Instant.ofEpochMilli(epochMillis.longValue()));
		}

	}

}
