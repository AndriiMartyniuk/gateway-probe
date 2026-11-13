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

The default listener (`LoggingConnectionEventListener`) logs at DEBUG:
`--logging.level.ua.kpi.iot.probe.transport=DEBUG`. Metrics will be another listener.

Known limit (by design of java-coap): CoAP request processing is single-threaded per listener. The queue depth
(`NettyCoapTcpServerTransport#inboundQueueSize`) shows when it becomes the bottleneck.

## Run

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

The image is built by the multi-stage `Dockerfile` (Temurin 25 JDK → JRE, non-root user). Ports: 5683 (coap+tcp),
5684 (coaps+tcp), 8080 (HTTP / actuator), 6379 (Redis, debugging only). TCP only:
`PROBE_LWM2M_TLS_ENABLED=false docker compose up -d`. If 6379 is taken on the host: `REDIS_HOST_PORT=6380 docker compose up -d`.

### HTTP / actuator

Spring MVC on port 8080. Exposed actuator endpoints: `/actuator/health` (with details, incl. Redis),
`/actuator/health/liveness`, `/actuator/health/readiness` (used by the compose health check), `/actuator/info`.

Tests (`./gradlew test`, Docker and `openssl` required) start the app on random ports and use real Leshan clients:
registration over TCP and TLS, the connection event sequence, a failed TLS handshake, and 20 devices registering in
parallel over TLS with 4 I/O threads.

## Notes

- Leshan's TCP/TLS transport is labelled *experimental* by Leshan; the TLS endpoint supports X.509 only (no PSK/RPK).
- Netty is aligned to the Spring Boot BOM version (4.2.x); our transport uses the 4.2 API
  (`MultiThreadIoEventLoopGroup`). Leshan's codec classes are compiled against an older 4.x line; tests pass.
