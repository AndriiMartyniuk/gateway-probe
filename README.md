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
endpoints are replaced by:

| Endpoint | URI | Property prefix |
|---|---|---|
| CoAP over TCP | `coap+tcp://<host>:5683` | `probe.lwm2m.tcp.*` |
| CoAP over TLS (TLS 1.2, mutual X.509) | `coaps+tcp://<host>:5684` | `probe.lwm2m.tls.*` |

TLS devices must present a certificate signed by a CA in `trusted-certificates`, with CN = endpoint name, and the
endpoint must be listed in `probe.lwm2m.tls.x509-endpoints` (Leshan `InMemorySecurityStore`). Registrations are only
logged. There are no metrics or custom policies yet.

Code: `src/main/java/ua/kpi/iot/probe/lwm2m/` (`Lwm2mServerProperties`, `Lwm2mServerConfiguration`,
`Lwm2mServerLifecycle`).

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

Tests (`./gradlew test`, Docker and `openssl` required) start the app on random ports and register a real Leshan
client over each transport.

## Notes

- Leshan's TCP/TLS transport is labelled *experimental* by Leshan; the TLS endpoint supports X.509 only (no PSK/RPK).
- Netty is aligned to the Spring Boot BOM version (4.2.x); Leshan/java-coap are compiled against an older 4.x line.
  The tests pass, but keep this in mind if transport issues appear.
