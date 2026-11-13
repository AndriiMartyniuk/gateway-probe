# gateway-probe

Minimal LwM2M gateway used as the **measurement stand for article 1**. It implements only what is needed to observe the
cost of each recovery phase — it is not the production-shaped `lwm2m-gateway`.

## Responsibilities

- Terminate TLS over TCP (Netty `SslHandler`) and accept CoAP over TCP.
- Handle Register / Update, establish m observations per device.
- **Per-phase counters**: TLS handshake (full vs resumption), Register, observation re-establishment — CPU time, round
  trips, bytes, latency; exported via Micrometer → Prometheus.
- Two state-placement variants, switched by configuration:
  - `in-memory` — state lives in the instance;
  - `redis` — registrations and observation specs in an external session store.
- Crash/restart hooks so E3 can compare model-predicted loss with actual loss.

## Experiments served

- **E1** recovery cost of one session per component.
- **E2** recovery time of one instance's sessions for N = 1k, 5k, 10k, in-memory vs Redis.
- **E3** model check against `session-model` predictions.

## First-week check

Eclipse Leshan's CoAP-over-TCP support is believed immature. Verify it first; if unusable, the protocol layer here is
written directly on Netty.

## Stack

Spring Boot 4.1, Eclipse Leshan 2.0.0-M18 (java-coap + Netty transport), Spring Data Redis, Micrometer/Prometheus,
Testcontainers (Redis) for tests.

## Current state: basic Leshan server

An embedded Leshan LwM2M server starts and stops with the Spring context (`SmartLifecycle`). Leshan's default UDP
endpoints are replaced by CoAP over TCP and CoAP over TLS endpoints running on **our own Netty transport**:

| Endpoint | URI | Property prefix |
|---|---|---|
| CoAP over TCP | `coap+tcp://<host>:5683` | `probe.lwm2m.tcp.*` |
| CoAP over TLS (TLS 1.2, mutual X.509) | `coaps+tcp://<host>:5684` | `probe.lwm2m.tls.*` |

TLS devices must present a certificate signed by a CA in `trusted-certificates`, with CN = endpoint name, and the
endpoint must be listed in `probe.lwm2m.tls.x509-endpoints` (Leshan `InMemorySecurityStore`). Registrations are only
logged.

Code: `src/main/java/ua/kpi/iot/probe/lwm2m/` (Spring wiring) and `src/main/java/ua/kpi/iot/probe/transport/`
(transport).

### Transport

Leshan's `NettyCoapTcpTransport` is not usable for measurements: its pipeline is private (no hooks), it runs all
connections on **one** Netty thread with `SO_BACKLOG` 100, and its single-promise receive drops packets if more than one
thread decodes. `NettyCoapTcpServerTransport` replaces it; Leshan still handles LwM2M (registration, observe, security),
and its public codec/context classes are reused.

| | Leshan transport | `NettyCoapTcpServerTransport` |
|---|---|---|
| I/O + TLS handshake threads | 1 | `probe.lwm2m.transport.io-threads` (0 = 2 × CPUs) |
| Accept backlog | 100 | `backlog` (default 1024) |
| Idle timeout / handshake timeout | disabled / Netty default | `idle-timeout`, `tls-handshake-timeout` |
| Hand-off to java-coap | promise completed on the I/O thread | queue + one dispatch thread (java-coap's `CoapServer` processes one packet at a time) |
| Lifecycle events | none | `ConnectionEventListener` |
| Thread shutdown on stop | no | yes |

`ConnectionEventListener` events (called on I/O threads, must not block):

| Event | Data |
|---|---|
| `onAccepted` | connection id, remote address, tls flag |
| `onTlsHandshake` | duration since accept, **resumed** (abbreviated handshake) or full, failure cause |
| `onEstablished` | connection is ready for CoAP (after the handshake for TLS) |
| `onClosed` | reason (`PEER_CLOSED`, `TLS_HANDSHAKE_FAILED`, `IDLE_TIMEOUT`, `ERROR`, `SERVER_CLOSED`, `SERVER_SHUTDOWN`), lifetime |

Every `ConnectionEventListener` bean receives these events (`CompositeConnectionEventListener`).
`LoggingConnectionEventListener` logs them at DEBUG: `--logging.level.ua.kpi.iot.probe.transport=DEBUG`.

### Probe Sensor object and server-initiated observations

Over TCP an observation exists only as long as its connection (RFC 8323), so after every Register the server has to
create the observations again. `ObservationProvisioner` does this: for each instance of the objects in
`probe.observe.object-ids` that the device lists in its registration, it sends one Observe, asynchronously, so the
CoAP dispatch thread never waits for the device.

The observed object is our own **Probe Sensor** (object 32700), defined in the shared library
[`lwm2m-objects`](../lwm2m-objects) (`ua.kpi.iot:lwm2m-objects`, published to the local Maven repository):

| ID | Resource | Type | Purpose |
|---|---|---|---|
| 0 | Sequence Number | Integer, R | +1 per sample: gaps = lost notifications, repeats = duplicates |
| 1 | Sample Time | Integer (ms since epoch), R | data age = server receive time − sample time |
| 2 | Value | Float, R | the synthetic measurement |
| 3 | Payload | Opaque, R | filler of Payload Size bytes, to control notification size |
| 4 | Sample Period | Integer (ms), RW | sampling rate; 0 stops |
| 5 | Payload Size | Integer (B), RW | size of resource 3 |

- Multiple instances: *m* instances = *m* independent streams = *m* observations per device.
- Object ID **32700**: private research object (`Source` 2), in the OMNA x-label range 26241–32768, not registered in
  the OMNA `lwm2m-registry` when chosen (2026-11). Not for production use.
- Sample Time is an Integer in milliseconds on purpose: the LwM2M `Time` type has one-second resolution.
- Data age compares the device clock with the server clock; it is meaningful when both run on one host or are
  synchronised (NTP), and negative ages are not recorded in the histogram.
- The device side for tests is `src/test/java/ua/kpi/iot/probe/ProbeSensorInstance.java` (Leshan client);
  `fleet-emulator` uses the same library.

Observation metrics (in addition to the table below): `probe_lwm2m_observe_latency_seconds{outcome}` (Observe sent →
device response), `probe_lwm2m_notification_age_seconds`, `probe_lwm2m_notifications_missing_total`,
`probe_lwm2m_notifications_duplicate_total`. Event log: `observe_sent`, `observe_done` (`ms` = latency, `reason` on
failure), and `notification` lines with `seq` and `ms` = data age. Notifications are logged only with
`probe.event-log.notifications=true`, **except** out-of-sequence ones (`reason` = `gap` / `duplicate`), which are
always logged.

Options: `probe.observe.enabled` (default `true`), `probe.observe.object-ids` (default `32700`),
`probe.observe.timeout` (default `10s`).

### Telemetry: metrics and per-session event log

`SessionTelemetry` (`src/main/java/ua/kpi/iot/probe/telemetry/`) listens to the transport *and* to Leshan
(registrations, observations). It joins a registration to its connection by the device's remote address, so the
registration latency is measured from TLS completion on the same connection. It publishes to two sinks:

```
transport events ──┐                     ┌─► ProbeMetrics ──► /actuator/prometheus   (live, aggregated)
Leshan registration├─► SessionTelemetry ─┤
Leshan observation ┘                     └─► SessionEventLog ─► event-logs/*.jsonl   (raw, per session)
```

**Micrometer metrics** (`/actuator/prometheus`):

| Metric | Type | Tags |
|---|---|---|
| `probe_connections_accepted_total` | counter | `transport` |
| `probe_connections_open` | gauge (accepted, not closed) | `transport` |
| `probe_connections_established` | gauge (ready for CoAP, not closed) | `transport` |
| `probe_connections_closed_total` | counter | `transport`, `reason` |
| `probe_connections_lifetime_seconds` | timer | `transport` |
| `probe_tls_handshake_seconds` | timer + histogram | `outcome`, `resumed` |
| `probe_lwm2m_register_latency_seconds` | timer + histogram (established → registered) | `transport` |
| `probe_lwm2m_registrations_total` | counter | `event` = registered / updated / deregistered / expired / replaced |
| `probe_lwm2m_registrations_active` | gauge | — |
| `probe_lwm2m_observations_total` | counter | `event` = created / cancelled / error |
| `probe_lwm2m_notifications_total` | counter | — |
| `probe_transport_dispatch_queue` | gauge (packets waiting for the java-coap thread) | `transport` |

Plus the standard JVM / process / executor metrics from Spring Boot Actuator.

**Per-session event log** (experiment tooling, off by default): one JSON object per line, written by a background
thread so I/O threads never wait on disk; no event is dropped.

```bash
./gradlew bootRun --args='--probe.event-log.enabled=true'      # -> event-logs/events-<start>-<pid>.jsonl
PROBE_EVENT_LOG_ENABLED=true docker compose up -d              # -> ./event-logs (mounted)
```

```json
{"t":1794607341412397,"event":"accepted","conn":"c843772e","transport":"tls"}
{"t":1794607341511893,"event":"tls_handshake","conn":"c843772e","transport":"tls","ms":97.777125,"resumed":false}
{"t":1794607341512018,"event":"established","conn":"c843772e","transport":"tls","ms":97.777125}
{"t":1794607341518123,"event":"closed","conn":"c843772e","transport":"tls","ms":106.162041,"reason":"PEER_CLOSED"}
```

(Format example from a single `openssl s_client` connection on a cold JVM — not a measurement.)

| Field | Meaning |
|---|---|
| `t` | wall clock, µs since epoch (ordering, aligning with other sources) |
| `event` | `accepted`, `tls_handshake`, `established`, `closed`, `registered`, `updated`, `unregistered`, `observation_created`, `observation_cancelled`, `observation_error`, `notification` |
| `conn` / `ep` | connection id / LwM2M endpoint (joins transport and LwM2M events) |
| `ms` | duration from a monotonic clock: since accept (`tls_handshake`, `established`), lifetime (`closed`), since established (`registered`) |
| `resumed`, `reason`, `regId`, `path`, `observations` | event-specific |

Options: `probe.event-log.directory` (default `event-logs`, git-ignored), `probe.event-log.notifications`
(log every notification; default `false`). Copy the files of a run to `experiments/<exp>/raw/<run>/`.

Known limit (by design of java-coap): CoAP request processing is single-threaded per listener. The queue depth
(`NettyCoapTcpServerTransport#inboundQueueSize`) shows when it becomes the bottleneck.

### Finding: connection loss without Deregister (Leshan 2.0.0-M18)

Characterised by `connectionLossWithoutDeregisterLeavesStaleSessionUntilReRegister`:

1. When the TCP connection drops without Deregister, Leshan **keeps the registration and its observations** until
   the device registers again or the lifetime expires — although over TCP the observations died with the connection
   (RFC 8323). The server-side session looks alive.
2. Requests sent to that stale registration fail (no connection to the device).
3. On reconnect, the new Register **replaces** the stale registration (`unregistered`, `reason=replaced`, with the
   number of dropped observations) and the probe observes again — no duplicates.

So, between the drop and the re-registration, server state and reality disagree. This is input for E3 and the
session-state model.

## Run

Requires the shared library in the local Maven repository:
`(cd ../lwm2m-objects && ./gradlew publishToMavenLocal)`.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
scripts/generate-dev-certs.sh            # dev CA, server.p12, client cert for dev-device-1 -> ./certs (git-ignored)
./gradlew bootRun
```

TCP only: `./gradlew bootRun --args='--probe.lwm2m.tls.enabled=false'`.

### Docker Compose (app + Redis)

```bash
scripts/generate-dev-certs.sh            # mounted read-only into the container at /app/certs
docker compose up -d --build
docker compose logs -f gateway-probe
docker compose down
```

The image build copies `ua.kpi.iot` artifacts from the host's `~/.m2` (compose `additional_contexts: m2`), so publish
`lwm2m-objects` locally first; a plain `docker build .` without that context fails. The image is built by the
multi-stage `Dockerfile` (Temurin 25 JDK → JRE, non-root user). Ports: 5683 (coap+tcp),
5684 (coaps+tcp), 8080 (HTTP / actuator), 6379 (Redis, debugging only). TCP only:
`PROBE_LWM2M_TLS_ENABLED=false docker compose up -d`. If 6379 is taken on the host: `REDIS_HOST_PORT=6380 docker compose up -d`.

### HTTP / actuator

Spring MVC on port 8080. Exposed actuator endpoints: `/actuator/health` (with details, incl. Redis),
`/actuator/health/liveness`, `/actuator/health/readiness` (used by the compose health check), `/actuator/info`.

Tests (`./gradlew test`, Docker and `openssl` required) start the app on random ports and use real Leshan clients:
registration over TCP and TLS, the connection event sequence, a failed TLS handshake, 20 devices registering in
parallel over TLS with 4 I/O threads, metrics and event log contents, and server-initiated observation of three Probe
Sensor instances including detection of a deliberately skipped sequence range.

## Notes

- Leshan's TCP/TLS transport is labelled *experimental* by Leshan; the TLS endpoint supports X.509 only (no PSK/RPK).
- Netty is aligned to the Spring Boot BOM version (4.2.x); our transport uses the 4.2 API
  (`MultiThreadIoEventLoopGroup`). Leshan's codec classes are compiled against an older 4.x line; tests pass.
