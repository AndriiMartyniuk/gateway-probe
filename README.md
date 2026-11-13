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

Spring Boot 4.1, Netty, Spring Data Redis, Micrometer/Prometheus, Testcontainers (Redis) for tests.

Status: empty skeleton.
