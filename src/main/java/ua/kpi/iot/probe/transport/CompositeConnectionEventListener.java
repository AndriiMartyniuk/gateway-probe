package ua.kpi.iot.probe.transport;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fans events out to several listeners, in order. A failing listener does not affect the others or the pipeline. */
public class CompositeConnectionEventListener implements ConnectionEventListener {

	private static final Logger log = LoggerFactory.getLogger(CompositeConnectionEventListener.class);

	private final List<ConnectionEventListener> listeners;

	public CompositeConnectionEventListener(List<? extends ConnectionEventListener> listeners) {
		this.listeners = List.copyOf(listeners);
	}

	@Override
	public void onAccepted(ConnectionInfo connection) {
		each(l -> l.onAccepted(connection));
	}

	@Override
	public void onTlsHandshake(ConnectionInfo connection, Duration duration, boolean resumed, Throwable failure) {
		each(l -> l.onTlsHandshake(connection, duration, resumed, failure));
	}

	@Override
	public void onEstablished(ConnectionInfo connection, Duration sinceAccept) {
		each(l -> l.onEstablished(connection, sinceAccept));
	}

	@Override
	public void onClosed(ConnectionInfo connection, CloseReason reason, Duration lifetime) {
		each(l -> l.onClosed(connection, reason, lifetime));
	}

	@Override
	public void onCoapMessage(ConnectionInfo connection, Direction direction, CoapMessageKind kind, int size,
			String token) {
		each(l -> l.onCoapMessage(connection, direction, kind, size, token));
	}

	private void each(Consumer<ConnectionEventListener> call) {
		for (ConnectionEventListener listener : listeners) {
			try {
				call.accept(listener);
			}
			catch (RuntimeException ex) {
				log.warn("Connection event listener {} failed", listener.getClass().getName(), ex);
			}
		}
	}

}
