#!/usr/bin/env python3
"""Generates the Grafana dashboards (dashboards/*.json) for gateway-probe and fleet-emulator.

Run after changing metrics or panels:  python3 monitoring/grafana/build-dashboards.py
Standard library only. Panel queries use the metric names exported by the two applications (Micrometer -> Prometheus).
"""
import json
import os

DS = {"type": "prometheus", "uid": "prometheus"}
HERE = os.path.dirname(os.path.abspath(__file__))
RATE = "[$__rate_interval]"


class Dashboard:

    def __init__(self, uid, title, description, instance_metric):
        self.uid, self.title, self.description = uid, title, description
        self.instance_metric = instance_metric
        self.panels = []
        self.y = 0
        self.x = 0
        self.row_height = 0
        self.next_id = 1

    def row(self, title):
        self._newline()
        self.panels.append({"type": "row", "title": title, "id": self._id(), "collapsed": False,
                            "gridPos": {"h": 1, "w": 24, "x": 0, "y": self.y}, "panels": []})
        self.y += 1

    def timeseries(self, title, targets, unit="short", w=8, h=8, description="", stack=False, min_zero=True):
        custom = {"lineWidth": 1, "fillOpacity": 25 if stack else 8, "showPoints": "never",
                  "stacking": {"mode": "normal" if stack else "none", "group": "A"}}
        defaults = {"unit": unit, "custom": custom}
        if min_zero:
            defaults["min"] = 0
        self._add({"type": "timeseries", "title": title, "description": description,
                   "fieldConfig": {"defaults": defaults, "overrides": []},
                   "options": {"legend": {"displayMode": "list", "placement": "bottom"},
                               "tooltip": {"mode": "multi", "sort": "desc"}},
                   "targets": self._targets(targets)}, w, h)

    def stat(self, title, targets, unit="short", w=4, h=4, description="", decimals=None):
        defaults = {"unit": unit, "color": {"mode": "thresholds"},
                    "thresholds": {"mode": "absolute", "steps": [{"color": "blue", "value": None}]}}
        if decimals is not None:
            defaults["decimals"] = decimals
        self._add({"type": "stat", "title": title, "description": description,
                   "fieldConfig": {"defaults": defaults, "overrides": []},
                   "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                               "colorMode": "value", "graphMode": "area", "textMode": "auto"},
                   "targets": self._targets(targets)}, w, h)

    def _targets(self, targets):
        return [{"datasource": DS, "expr": expr, "legendFormat": legend, "refId": chr(65 + i), "range": True}
                for i, (expr, legend) in enumerate(targets)]

    def _add(self, panel, w, h):
        if self.x + w > 24:
            self._newline()
        panel.update({"id": self._id(), "datasource": DS, "gridPos": {"h": h, "w": w, "x": self.x, "y": self.y}})
        self.panels.append(panel)
        self.x += w
        self.row_height = max(self.row_height, h)

    def _newline(self):
        if self.x:
            self.y += self.row_height
        self.x, self.row_height = 0, 0

    def _id(self):
        self.next_id += 1
        return self.next_id - 1

    def json(self):
        return {
            "uid": self.uid, "title": self.title, "description": self.description,
            "tags": ["lwm2m", "phd", "recovery"], "timezone": "browser", "editable": True,
            "schemaVersion": 41, "version": 1, "refresh": "5s", "graphTooltip": 1,
            "time": {"from": "now-15m", "to": "now"},
            "templating": {"list": [{
                "name": "instance", "label": "instance", "type": "query", "datasource": DS,
                "query": {"query": f"label_values({self.instance_metric}, instance)", "refId": "instance"},
                "definition": f"label_values({self.instance_metric}, instance)",
                "includeAll": True, "multi": True, "allValue": ".*", "refresh": 2, "sort": 1,
                "current": {"selected": True, "text": ["All"], "value": ["$__all"]},
            }]},
            "panels": self.panels,
            "links": [{"title": "LwM2M recovery", "type": "dashboards", "tags": ["lwm2m"], "asDropdown": True}],
        }


def sel(extra=""):
    """Label selector restricted to the chosen instances."""
    return "{" + 'instance=~"$instance"' + ("," + extra if extra else "") + "}"


def q(quantile, metric, by="", extra=""):
    group = f"le{', ' + by if by else ''}"
    return f"histogram_quantile({quantile}, sum by ({group}) (rate({metric}_bucket{sel(extra)}{RATE})))"


def avg(metric, by, extra=""):
    return (f"sum by ({by}) (rate({metric}_sum{sel(extra)}{RATE})) / "
            f"sum by ({by}) (rate({metric}_count{sel(extra)}{RATE}))")


def rate(metric, by="", extra=""):
    inner = f"rate({metric}{sel(extra)}{RATE})"
    return f"sum by ({by}) ({inner})" if by else f"sum({inner})"


# ---------------------------------------------------------------------------------------------------- gateway-probe
def probe_dashboard():
    d = Dashboard("gateway-probe", "Gateway probe",
                  "Server side of the LwM2M stand: connections, TLS, LwM2M sessions, CoAP traffic, dispatch, state.",
                  "probe_connections_open")

    d.row("Overview")
    d.stat("Registrations active", [(f"sum(probe_lwm2m_registrations_active{sel()})", "")])
    d.stat("Connections established", [(f"sum(probe_connections_established{sel()})", "")])
    d.stat("Registrations / s", [(rate("probe_lwm2m_registrations_total", extra='event="registered"'), "")],
           unit="reqps", decimals=1)
    d.stat("Observe failures / s", [(rate("probe_lwm2m_observe_latency_seconds_count", extra='outcome="failure"'), "")],
           unit="reqps", decimals=2)
    d.stat("Dispatch queue", [(f"sum(probe_transport_dispatch_queue{sel()})", "")])
    d.stat("Process CPU", [(f"avg(process_cpu_usage{sel()})", "")], unit="percentunit", decimals=1)
    d.timeseries("Registered devices: probe vs emulator",
                 [(f"sum(probe_lwm2m_registrations_active{sel()})", "probe: registrations active"),
                  ('sum(emulator_devices{state="REGISTERED"})', "emulator: devices registered")], w=24, h=8,
                 description="Recovery curve: how many devices are registered over time (T_rec is read here).")

    d.row("Connections and TLS")
    d.timeseries("Connections", [(f"sum by (transport) (probe_connections_established{sel()})", "established {{transport}}"),
                                 (f"sum by (transport) (probe_connections_open{sel()})", "open {{transport}}")])
    d.timeseries("Accepted / closed per second",
                 [(rate("probe_connections_accepted_total", "transport"), "accepted {{transport}}"),
                  (rate("probe_connections_closed_total", "reason"), "closed {{reason}}")], unit="reqps")
    d.timeseries("TLS handshakes per second",
                 [(rate("probe_tls_handshake_seconds_count", "outcome, resumed"), "{{outcome}} resumed={{resumed}}")],
                 unit="reqps")
    d.timeseries("TLS handshake duration p50 / p95",
                 [(q(0.5, "probe_tls_handshake_seconds", "resumed", 'outcome="success"'), "p50 resumed={{resumed}}"),
                  (q(0.95, "probe_tls_handshake_seconds", "resumed", 'outcome="success"'), "p95 resumed={{resumed}}")],
                 unit="s", description="TCP accept to handshake completion (server side).")
    d.timeseries("TLS handshake wire bytes (mean)",
                 [(avg("probe_tls_handshake_bytes", "direction, resumed"), "{{direction}} resumed={{resumed}}")],
                 unit="bytes")
    d.timeseries("TLS handshake flights (mean)",
                 [(avg("probe_tls_handshake_flights", "resumed"), "resumed={{resumed}}")])

    d.row("LwM2M sessions")
    d.timeseries("Registration events per second",
                 [(rate("probe_lwm2m_registrations_total", "event"), "{{event}}")], unit="reqps")
    d.timeseries("Register latency (established → registered)",
                 [(q(0.5, "probe_lwm2m_register_latency_seconds"), "p50"),
                  (q(0.95, "probe_lwm2m_register_latency_seconds"), "p95"),
                  (q(0.99, "probe_lwm2m_register_latency_seconds"), "p99")], unit="s")
    d.timeseries("Observe latency (sent → answered)",
                 [(q(0.5, "probe_lwm2m_observe_latency_seconds", extra='outcome="success"'), "p50"),
                  (q(0.95, "probe_lwm2m_observe_latency_seconds", extra='outcome="success"'), "p95"),
                  (rate("probe_lwm2m_observe_latency_seconds_count", extra='outcome="failure"'), "failures / s")],
                 unit="s")
    d.timeseries("Observations per second", [(rate("probe_lwm2m_observations_total", "event"), "{{event}}")],
                 unit="reqps")
    d.timeseries("Notifications", [(rate("probe_lwm2m_notifications_total"), "received / s"),
                                   (rate("probe_lwm2m_notifications_missing_total"), "missing / s"),
                                   (rate("probe_lwm2m_notifications_duplicate_total"), "duplicate / s")],
                 unit="reqps")
    d.timeseries("Digital-twin data age (notification)",
                 [(q(0.5, "probe_lwm2m_notification_age_seconds"), "p50"),
                  (q(0.95, "probe_lwm2m_notification_age_seconds"), "p95")], unit="s",
                 description="Server receive time minus device sample time (wall clocks).")

    d.row("CoAP traffic")
    d.timeseries("CoAP messages per second by kind",
                 [(rate("probe_coap_messages_bytes_count", "direction, kind"), "{{direction}} {{kind}}")],
                 unit="reqps", w=12, h=9)
    d.timeseries("CoAP bytes per second by kind (no TLS overhead)",
                 [(f"sum by (direction, kind) (rate(probe_coap_messages_bytes_sum{sel()}{RATE}))",
                   "{{direction}} {{kind}}")], unit="Bps", w=12, h=9)
    d.timeseries("Mean CoAP message size by kind",
                 [(avg("probe_coap_messages_bytes", "direction, kind"), "{{direction}} {{kind}}")], unit="bytes",
                 w=12)
    d.timeseries("Wire bytes per closed connection (mean)",
                 [(avg("probe_connections_bytes", "transport, direction"), "{{transport}} {{direction}}")],
                 unit="bytes", w=12)

    d.row("Dispatch and state placement")
    d.timeseries("Dispatch queue (packets waiting for java-coap)",
                 [(f"sum by (transport) (probe_transport_dispatch_queue{sel()})", "{{transport}}")])
    d.timeseries("Redis replication lag (REDIS_CACHED)",
                 [(q(0.5, "probe_state_redis_write_latency_seconds"), "p50"),
                  (q(0.95, "probe_state_redis_write_latency_seconds"), "p95"),
                  (f"sum(probe_state_redis_write_pending{sel()})", "pending writes")], unit="s",
                 description="Change in memory → acknowledged by Redis. 'pending writes' is a count (same axis).")
    d.timeseries("Redis pool (REDIS) and write failures",
                 [(f"sum(probe_state_redis_pool_active{sel()})", "pool active"),
                  (f"sum(probe_state_redis_pool_waiters{sel()})", "pool waiters"),
                  (rate("probe_state_redis_write_failures_total", "operation"), "write failures/s {{operation}}")])

    d.row("JVM")
    d.timeseries("Process CPU", [(f"process_cpu_usage{sel()}", "{{instance}}")], unit="percentunit")
    heap = sel('area="heap"')
    d.timeseries("Heap used", [(f"sum by (instance) (jvm_memory_used_bytes{heap})", "{{instance}}")], unit="bytes")
    d.timeseries("Threads and GC",
                 [(f"sum by (instance) (jvm_threads_live_threads{sel()})", "live threads {{instance}}"),
                  (f"sum by (instance) (rate(jvm_gc_pause_seconds_sum{sel()}{RATE}))", "GC pause s/s {{instance}}")])
    return d


# --------------------------------------------------------------------------------------------------- fleet-emulator
def emulator_dashboard():
    d = Dashboard("fleet-emulator", "Fleet emulator",
                  "Client side of the LwM2M stand: device states, reconnects, client-side TLS and Register latency.",
                  "emulator_devices")

    d.row("Fleet")
    for state in ("REGISTERED", "CONNECTING", "REGISTERING", "WAITING_RECONNECT"):
        selector = sel(f'state="{state}"')
        d.stat(state.replace("_", " ").title(), [(f"sum(emulator_devices{selector})", "")])
    d.stat("Connect attempts / s", [(rate("emulator_connect_attempts_total"), "")], unit="reqps", decimals=1)
    d.stat("Connections lost / s", [(rate("emulator_connections_lost_total"), "")], unit="reqps", decimals=1)
    d.timeseries("Devices by state", [(f"sum by (state) (emulator_devices{sel()})", "{{state}}")], w=24, h=9,
                 stack=True)

    d.row("Connect and reconnect")
    d.timeseries("Connect attempts and failures per second",
                 [(rate("emulator_connect_attempts_total"), "attempts"),
                  (rate("emulator_connect_failures_total", "cause"), "failures {{cause}}"),
                  (rate("emulator_connections_lost_total"), "connections lost")], unit="reqps")
    d.timeseries("TCP connect latency", [(q(0.5, "emulator_connect_latency_seconds"), "p50"),
                                         (q(0.95, "emulator_connect_latency_seconds"), "p95")], unit="s")
    d.timeseries("Reconnect delay chosen by the strategy (mean)",
                 [(avg("emulator_reconnect_delay_seconds", "instance"), "{{instance}}")], unit="s")

    d.row("TLS and registration (client side)")
    d.timeseries("TLS handshakes per second",
                 [(rate("emulator_tls_handshake_seconds_count", "outcome, resumed"), "{{outcome}} resumed={{resumed}}")],
                 unit="reqps")
    d.timeseries("TLS handshake duration p50 / p95",
                 [(q(0.5, "emulator_tls_handshake_seconds", "resumed", 'outcome="success"'), "p50 resumed={{resumed}}"),
                  (q(0.95, "emulator_tls_handshake_seconds", "resumed", 'outcome="success"'), "p95 resumed={{resumed}}")],
                 unit="s")
    d.timeseries("Register latency (sent → 2.01)", [(q(0.5, "emulator_register_latency_seconds"), "p50"),
                                                    (q(0.95, "emulator_register_latency_seconds"), "p95"),
                                                    (q(0.99, "emulator_register_latency_seconds"), "p99")], unit="s")
    d.timeseries("Registration failures per second",
                 [(rate("emulator_registration_failures_total", "operation, reason"), "{{operation}} {{reason}}")],
                 unit="reqps", w=12)

    d.row("Observations and notifications")
    d.timeseries("Observe received / notifications sent per second",
                 [(rate("emulator_observe_received_total"), "observe received"),
                  (rate("emulator_notifications_sent_total"), "notifications sent")], unit="reqps", w=12)
    d.timeseries("Notification payload (mean)",
                 [(avg("emulator_notifications_payload_bytes", "instance"), "{{instance}}")], unit="bytes", w=12)

    d.row("JVM")
    d.timeseries("Process CPU", [(f"process_cpu_usage{sel()}", "{{instance}}")], unit="percentunit", w=12)
    d.timeseries("Threads", [(f"sum by (instance) (jvm_threads_live_threads{sel()})", "{{instance}}")], w=12)
    return d


def main():
    out = os.path.join(HERE, "dashboards")
    os.makedirs(out, exist_ok=True)
    for dashboard in (probe_dashboard(), emulator_dashboard()):
        path = os.path.join(out, f"{dashboard.uid}.json")
        with open(path, "w") as f:
            json.dump(dashboard.json(), f, indent=2)
        print(f"{path}: {sum(1 for p in dashboard.panels if p['type'] != 'row')} panels")


if __name__ == "__main__":
    main()
