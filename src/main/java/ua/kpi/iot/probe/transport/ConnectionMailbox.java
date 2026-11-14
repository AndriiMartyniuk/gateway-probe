package ua.kpi.iot.probe.transport;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sequential task queue of one connection ({@link DispatchMode#VIRTUAL}): tasks run in submission order, never two at
 * the same time, on threads of the given executor (virtual threads). At most one drain task is scheduled at a time.
 */
final class ConnectionMailbox {

	private static final Logger log = LoggerFactory.getLogger(ConnectionMailbox.class);

	private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

	private final AtomicBoolean scheduled = new AtomicBoolean();

	private final Executor executor;

	/** Tasks queued in all mailboxes of a transport, for the dispatch-queue gauge. */
	private final AtomicInteger queuedTotal;

	ConnectionMailbox(Executor executor, AtomicInteger queuedTotal) {
		this.executor = executor;
		this.queuedTotal = queuedTotal;
	}

	void offer(Runnable task) {
		tasks.add(task);
		queuedTotal.incrementAndGet();
		schedule();
	}

	private void schedule() {
		if (scheduled.compareAndSet(false, true)) {
			try {
				executor.execute(this::drain);
			}
			catch (RejectedExecutionException ex) {
				// transport stopping: drop what is left
				scheduled.set(false);
			}
		}
	}

	private void drain() {
		Runnable task;
		while ((task = tasks.poll()) != null) {
			queuedTotal.decrementAndGet();
			try {
				task.run();
			}
			catch (RuntimeException ex) {
				log.error("Unexpected error while dispatching a CoAP message", ex);
			}
		}
		scheduled.set(false);
		// a task may have been added between the last poll and the reset of the flag
		if (!tasks.isEmpty()) {
			schedule();
		}
	}

}
