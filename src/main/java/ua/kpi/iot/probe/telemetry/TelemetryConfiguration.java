package ua.kpi.iot.probe.telemetry;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;

import org.eclipse.leshan.server.LeshanServer;
import org.eclipse.leshan.server.endpoint.LwM2mServerEndpointsProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import tools.jackson.databind.json.JsonMapper;
import ua.kpi.iot.probe.transport.CoapTcpServerEndpointsProvider;
import ua.kpi.iot.probe.transport.CoapsTcpServerEndpointsProvider;
import ua.kpi.iot.probe.transport.NettyCoapTcpServerTransport;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TelemetryProperties.class)
class TelemetryConfiguration {

	@Bean
	ProbeMetrics probeMetrics(MeterRegistry registry) {
		return new ProbeMetrics(registry);
	}

	@Bean
	@ConditionalOnProperty(name = "probe.event-log.enabled", havingValue = "true")
	JsonlSessionEventLog jsonlSessionEventLog(TelemetryProperties properties, JsonMapper json) {
		TelemetryProperties.EventLog config = properties.eventLog();
		String name = "events-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-"
				+ ProcessHandle.current().pid() + ".jsonl";
		return new JsonlSessionEventLog(config.directory().resolve(name), config.notifications(), json);
	}

	@Bean
	@ConditionalOnProperty(name = "probe.event-log.enabled", havingValue = "false", matchIfMissing = true)
	SessionEventLog disabledSessionEventLog() {
		return SessionEventLog.DISABLED;
	}

	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	SessionTelemetry sessionTelemetry(ProbeMetrics metrics, SessionEventLog eventLog) {
		return new SessionTelemetry(metrics, eventLog);
	}

	/** Gauges read from the running server: active registrations, open connections and dispatch queue depth. */
	@Bean
	MeterBinder lwm2mServerGauges(LeshanServer server) {
		return registry -> {
			registry.gauge("probe.lwm2m.registrations.active", Tags.empty(), server, s -> count(
					s.getRegistrationService().getAllRegistrations()));
			for (LwM2mServerEndpointsProvider provider : server.getEndpointsProvider()) {
				String transport = provider instanceof CoapsTcpServerEndpointsProvider ? "tls"
						: provider instanceof CoapTcpServerEndpointsProvider ? "tcp" : null;
				if (transport == null) {
					continue;
				}
				registry.gauge("probe.transport.dispatch.queue", Tags.of("transport", transport), provider,
						p -> metric(p, NettyCoapTcpServerTransport::inboundQueueSize));
			}
		};
	}

	private static double metric(LwM2mServerEndpointsProvider provider,
			java.util.function.ToIntFunction<NettyCoapTcpServerTransport> value) {
		NettyCoapTcpServerTransport transport = provider instanceof CoapsTcpServerEndpointsProvider tls
				? tls.transport() : ((CoapTcpServerEndpointsProvider) provider).transport();
		return transport == null ? Double.NaN : value.applyAsInt(transport);
	}

	private static double count(Iterator<?> iterator) {
		long n = 0;
		while (iterator.hasNext()) {
			iterator.next();
			n++;
		}
		return n;
	}

}
