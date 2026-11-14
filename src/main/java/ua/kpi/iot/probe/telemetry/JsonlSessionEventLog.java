package ua.kpi.iot.probe.telemetry;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import tools.jackson.databind.json.JsonMapper;

/**
 * Writes {@link SessionEvent}s as JSON lines. {@link #append} only enqueues; a single background thread serialises and
 * writes, so callers on I/O threads never wait for the disk. The queue is unbounded: no event is dropped.
 *
 * <p>
 * Runs in an early lifecycle phase so it starts before the LwM2M server and stops after it, capturing shutdown
 * events.
 */
public class JsonlSessionEventLog implements SessionEventLog, SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(JsonlSessionEventLog.class);

	private static final SessionEvent POISON = new SessionEvent(0, "", null, null, null, null, null, null, null, null,
			null, null, null, null, null, null, null, null, null);

	private final Path file;

	private final boolean notifications;

	private final boolean coapMessages;

	private final JsonMapper json;

	private final BlockingQueue<SessionEvent> queue = new LinkedBlockingQueue<>();

	private final AtomicLong written = new AtomicLong();

	private volatile Thread writer;

	public JsonlSessionEventLog(Path file, boolean notifications, boolean coapMessages, JsonMapper json) {
		this.file = file;
		this.notifications = notifications;
		this.coapMessages = coapMessages;
		this.json = json;
	}

	@Override
	public void append(SessionEvent event) {
		queue.add(event);
	}

	@Override
	public boolean logsNotifications() {
		return notifications;
	}

	@Override
	public boolean logsCoapMessages() {
		return coapMessages;
	}

	public Path file() {
		return file;
	}

	/** Events written to disk so far. */
	public long written() {
		return written.get();
	}

	@Override
	public synchronized void start() {
		BufferedWriter out;
		try {
			Files.createDirectories(file.toAbsolutePath().getParent());
			out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
					StandardOpenOption.APPEND);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Unable to open session event log " + file, ex);
		}
		writer = Thread.ofPlatform().name("session-event-log").daemon().start(() -> writeLoop(out));
		log.info("Session event log: {}", file.toAbsolutePath());
	}

	@Override
	public synchronized void stop() {
		Thread thread = writer;
		if (thread == null) {
			return;
		}
		queue.add(POISON);
		try {
			thread.join(10_000);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		writer = null;
		log.info("Session event log closed: {} events in {}", written.get(), file.toAbsolutePath());
	}

	@Override
	public boolean isRunning() {
		return writer != null;
	}

	@Override
	public int getPhase() {
		return 0;
	}

	private void writeLoop(BufferedWriter out) {
		List<SessionEvent> batch = new ArrayList<>();
		try (out) {
			while (true) {
				batch.add(queue.take());
				queue.drainTo(batch);
				boolean last = false;
				for (SessionEvent event : batch) {
					if (event == POISON) {
						last = true;
						break;
					}
					out.write(json.writeValueAsString(event));
					out.newLine();
					written.incrementAndGet();
				}
				out.flush();
				batch.clear();
				if (last) {
					return;
				}
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		catch (IOException | RuntimeException ex) {
			log.error("Session event log writer failed; further events are lost", ex);
		}
	}

}
