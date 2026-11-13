package ua.kpi.iot.probe.telemetry;

/** Sink for per-session events. Implementations must not block the caller (called on Netty I/O threads). */
public interface SessionEventLog {

	void append(SessionEvent event);

	/** Whether notifications are logged (they can dominate the log volume). */
	default boolean logsNotifications() {
		return false;
	}

	SessionEventLog DISABLED = event -> {
	};

}
