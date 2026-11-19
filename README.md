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

TLS devices must present a certificate signed by a CA in `trusted-certificates`, with CN = endpoint name. Two ways
to be accepted:

- listed in `probe.lwm2m.tls.x509-endpoints` (Leshan `InMemorySecurityStore`, Leshan's own check), or
- `probe.lwm2m.tls.accept-ca-signed-devices=true` (default): **any** device whose certificate chains to a trusted CA
  and whose CN equals the endpoint name (`CaSignedDeviceSecurityChecker`). Needed for emulated fleets of thousands of
  devices. A valid certificate presented under another endpoint name is refused (Register → 4.03).

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

### Wire traffic and CoAP messages per phase (E1)

Two more pipeline handlers in the transport:

- `TrafficCounter` (first, below TLS): wire bytes in/out per connection and, until the TLS handshake completes, the
  number of **flights** (changes of direction).
- `CoapMessageClassifier` (between codec and dispatcher): classifies every CoAP message in both directions —
  `CSM`, `PING`/`PONG`, `REGISTER` / `REGISTER_RESPONSE`, `UPDATE…`, `DEREGISTER…`, `OBSERVE` (server → device),
  `OBSERVE_RESPONSE`, `NOTIFICATION`, `OTHER_…` — matching responses to requests by token, with the serialised
  CoAP-over-TCP size (without TLS record overhead).

| Metric | Meaning |
|---|---|
| `probe_tls_handshake_bytes{direction,resumed}` | wire bytes of a successful handshake (accept → completion) |
| `probe_tls_handshake_flights{resumed}` | flights of a successful handshake |
| `probe_coap_messages_bytes{direction,kind}` | count and total size of CoAP messages by kind |
| `probe_connections_bytes{transport,direction}` | wire bytes per connection, recorded at close |

Event log: `tls_handshake` and `closed` carry `bytesIn`, `bytesOut` (and `flights`); with
`probe.event-log.coap-messages=true` every CoAP message is a `coap_message` line with `dir`, `kind`, `bytes` (for
single-session E1 runs; high volume).

Smoke run (500 TLS devices from `fleet-emulator`, one laptop — not a measurement): full mutual-TLS 1.2 handshakes
took 4 flights, resumed ones 3, as the protocol prescribes.

### Dispatch: how messages reach java-coap

```
socket ─► Netty I/O threads: TLS, CoAP decode, classifier ─► dispatch ─► java-coap CoapTcpDispatcher
                                                                     ─► Leshan (registration, observe, store)
```

java-coap processes inbound messages in a receive loop: it asks the transport for the next packet only after the
current one has been fully processed (Leshan handlers, store calls and listeners included). With Leshan's transport
that is one thread for all connections. `probe.lwm2m.transport.dispatch`:

- `SINGLE` (default): that loop — one dispatch thread per listener; anything slow in message processing delays every
  device.
- `VIRTUAL`: one mailbox per connection, drained by a virtual thread; the transport calls java-coap's
  `CoapTcpDispatcher.handle()` directly (java-coap passes the dispatcher to the transport as its TCP listener).
  Messages of one connection are processed in order, different connections in parallel; a blocking store call parks
  only that connection's virtual thread (JDK 24+ no longer pins carriers on `synchronized`, JEP 491). The
  disconnect notification goes through the same mailbox, after the connection's queued messages.

`probe_transport_dispatch_queue{transport}` = packets waiting (single queue, or all mailboxes).

### State placement (E2)

`probe.state.placement`:

| Placement | Store | Reads / writes | After an instance restart |
|---|---|---|---|
| `IN_MEMORY` (default) | Leshan `InMemoryRegistrationStore` | memory | state gone |
| `REDIS` | Leshan `RedisRegistrationStore` (Jedis) | every read and write is a blocking Redis round trip (per-endpoint Redis lock) | state in Redis |
| `REDIS_CACHED` | `RedisBackedRegistrationStore` (Lettuce) | reads from memory; writes to memory, then asynchronously to Redis | loaded from Redis on start (`probe.state.redis.load-on-start`) |

- `REDIS`: pool size `probe.state.redis.pool-size` (default 64; with `VIRTUAL` it bounds how many connections can
  wait on Redis at once); metrics `probe_state_redis_pool_active`, `probe_state_redis_pool_waiters`. Built against
  Jedis 4.4.6, runs on the Spring Boot BOM's 7.4.1 (tests and smoke runs).
- `REDIS_CACHED`: keys `<prefix>reg:<registrationId>`, `<prefix>obs:<registrationId>` (prefix
  `probe.state.redis.key-prefix`, default `probe:`); metrics `probe_state_redis_write_latency_seconds` (replication
  lag), `probe_state_redis_write_pending`, `probe_state_redis_write_failures_total{operation}`. A crash loses the
  changes not yet acknowledged — a parameter for the session-state model.
- Over TCP, a registration and its observations loaded from Redis after a restart refer to connections that no longer
  exist: devices reconnect and Register again, which replaces them (see the finding below).

#### Placement × dispatch (smoke runs, not measurements)

500 TLS devices × 2 Probe Sensors from `fleet-emulator`, 200 connections/s, then 100 connections dropped; emulator,
probe and Redis on one laptop. "Register processing" = connection established → registration accepted (event log).

| Placement · dispatch | Observes OK / 1200 | java-coap "Can not process" | Register processing p50 / p90 / max (ms) |
|---|---|---|---|
| IN_MEMORY · SINGLE | 1200 | 0 | 0.8 / 47 / 135 |
| IN_MEMORY · VIRTUAL | 1200 | 0 | 0.6 / 9 / 70 |
| REDIS · SINGLE | 1087 (113 lost) | 48 | 912 / 5112 / 5479 |
| REDIS · VIRTUAL | 1200 | 0 | 17 / 135 / 227 |
| REDIS_CACHED · SINGLE | 1200 | 0 | 0.8 / 42 / 83 |
| REDIS_CACHED · VIRTUAL | 1200 | 0 | 0.7 / 7 / 65 |

With `REDIS · SINGLE` the blocking Redis calls saturate the single dispatch thread: messages wait for seconds,
responses then find their requests already timed out or their connection closed (`Can not process CoAP message …`),
and Observes are lost. `VIRTUAL` removes the queueing across devices; each message still pays its own Redis round
trips (higher latency than in memory). `REDIS_CACHED` removes the round trips from the hot path at the cost of an
asynchronous replica (in these runs: replication lag mean ≈ 2–3 ms, max ≈ 30–38 ms).

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

### Finding: stale close of a connection whose address is reused (reconnection storm)

java-coap identifies a TCP peer by its address (`IP:port`) only. In a mass reconnect (theses stand, N ≥ 4000) the I/O
threads, busy with TLS handshakes, processed the close of a dead connection *after* a new connection from the same
address had become active; `onDisconnected(address)` then failed the new connection's Observe requests
(`SendFailedException: Socket closed`) and the device stayed partially observed. The transport now does not pass a close
to java-coap when a newer channel holds that address, and the telemetry tracks connections by id as well as address.
Such cases are recorded as the event `address_reused` and the counter `probe_connections_address_reused_total`
(13–107 per run at N = 4000–5000, no failures after the change).

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

### Monitoring: Prometheus + Grafana

`docker compose up -d` also starts **Prometheus** (http://localhost:9090) and **Grafana** (http://localhost:3000,
anonymous viewer; `admin` / `admin` to edit). Everything is provisioned from `monitoring/`:

```
monitoring/
  prometheus/prometheus.yml                  scrape config (5 s)
  grafana/provisioning/datasources/          Prometheus datasource (uid "prometheus")
  grafana/provisioning/dashboards/           file provider -> folder "LwM2M recovery"
  grafana/dashboards/*.json                  generated dashboards
  grafana/build-dashboards.py                generator: edit panels here, then run it
```

Scrape targets (`/actuator/prometheus`):

| Job | Targets | When |
|---|---|---|
| `gateway-probe` | `gateway-probe:8080` | probe in this compose project |
| `gateway-probe-host` | `host.docker.internal:8081`, `:18080` | probe on the host (`bootRun --server.port=8081`, E1 runs) |
| `fleet-emulator` | `host.docker.internal:8090`, `:18090` | emulator on the host (default, E1 runs) |

Targets that are not running show as `down` — expected.

Dashboards (refresh 5 s, `instance` variable):

- **Gateway probe** — Overview (registrations active, established connections, register rate, observe failures,
  dispatch queue, CPU; *registered devices: probe vs emulator* — the recovery curve); Connections and TLS
  (accepted/closed by reason, handshakes by kind, duration p50/p95, wire bytes, flights); LwM2M sessions (registration
  events, register / observe latency, observations, notifications, missing / duplicate, data age); CoAP traffic
  (messages and bytes per second by kind, mean size, wire bytes per connection); Dispatch and state placement
  (dispatch queue, Redis replication lag, Jedis pool, write failures); JVM.
- **Fleet emulator** — devices by state, connect attempts / failures / lost, connect latency, reconnect delay,
  client-side TLS handshake and Register latency, registration failures, observe / notification traffic, JVM.

All meters are registered at startup with every known tag combination (value 0). A meter created at its first event
appears in Prometheus already at its burst value; with no earlier sample, `rate()` / `increase()` would show the burst
as 0 — and bursts (reconnect waves) are what the dashboards are for.

Ports: `PROMETHEUS_PORT`, `GRAFANA_PORT`; retention `PROMETHEUS_RETENTION` (default 15d); data in the named volumes
`prometheus-data`, `grafana-data` (`docker compose down -v` removes them). After editing `prometheus.yml`:
`curl -XPOST localhost:9090/-/reload`. Placement and dispatch of the compose probe: `PROBE_STATE_PLACEMENT`,
`PROBE_LWM2M_TRANSPORT_DISPATCH`.

### HTTP / actuator

Spring MVC on port 8080. Exposed actuator endpoints: `/actuator/health` (with details, incl. Redis),
`/actuator/health/liveness`, `/actuator/health/readiness` (used by the compose health check), `/actuator/info`.

Tests (`./gradlew test`, Docker and `openssl` required) start the app on random ports and use real Leshan clients:
registration over TCP and TLS (listed and unlisted CA-signed devices; a CN/endpoint mismatch is refused), the
connection event sequence, a failed TLS handshake, 20 devices registering in
parallel over TLS with 4 I/O threads, metrics and event log contents (incl. handshake bytes / flights and CoAP message
classification), server-initiated observation of three Probe Sensor instances including detection of a deliberately
skipped sequence range, connection loss without Deregister, and (`RedisStatePlacementTests`, Testcontainers Redis)
recovery of registration and observations from Redis (`RedisStatePlacementTests` for `REDIS`,
`CachedRedisStatePlacementTests` for `REDIS_CACHED`) and their removal on Deregister. `VirtualDispatchTests` runs all
application tests again with `dispatch=VIRTUAL`.

## Notes

- Leshan's TCP/TLS transport is labelled *experimental* by Leshan; the TLS endpoint supports X.509 only (no PSK/RPK).
- Netty is aligned to the Spring Boot BOM version (4.2.x); our transport uses the 4.2 API
  (`MultiThreadIoEventLoopGroup`). Leshan's codec classes are compiled against an older 4.x line; tests pass.

## License

MIT, see [LICENSE](LICENSE). Dependencies keep their own licenses (Eclipse Leshan: EPL-2.0 / EDL-1.0; Netty, Spring Boot, java-coap: Apache-2.0).
