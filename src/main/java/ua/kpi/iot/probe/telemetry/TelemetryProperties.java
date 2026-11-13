package ua.kpi.iot.probe.telemetry;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param eventLog per-session JSONL event log (experiment tooling, off by default)
 */
@ConfigurationProperties("probe")
public record TelemetryProperties(@DefaultValue EventLog eventLog) {

	/**
	 * @param enabled write the log
	 * @param directory where {@code events-<start time>-<pid>.jsonl} is created
	 * @param notifications also log every observe notification (high volume)
	 * @param coapMessages also log every CoAP message with its kind and size (high volume; E1 single-session runs)
	 */
	public record EventLog(@DefaultValue("false") boolean enabled, @DefaultValue("event-logs") Path directory,
			@DefaultValue("false") boolean notifications, @DefaultValue("false") boolean coapMessages) {
	}

}
