import argparse
from datetime import datetime, timezone
import http.client
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
SCENARIOS = (
    ("planned-arrival", "awaiting_departure", []),
    ("estimated-arrival", "in_transit", []),
    ("actual-arrival", "arrived", []),
    ("late-departure", "arrived", []),
    ("inconsistent-chronology", "needs_review", ["inconsistent_chronology"]),
    ("conflicting-actuals", "needs_review", ["conflicting_actual_events"]),
)


def request(address, path, event=None):
    connection = http.client.HTTPConnection(address, timeout=5)
    try:
        if event is None:
            connection.request("GET", path)
        else:
            connection.request(
                "POST", path, json.dumps(event), {"Content-Type": "application/json"}
            )
        response = connection.getresponse()
        return response.status, json.loads(response.read())
    finally:
        connection.close()


def fixture(name):
    return json.loads((ROOT / "fixtures" / f"{name}.json").read_text(encoding="utf-8"))


def normalize(event):
    instant = datetime.fromisoformat(event["eventDateTime"].replace("Z", "+00:00"))
    event_time = instant.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
    return {**event, "eventDateTime": event_time}


def check_equal(label, expected, actual):
    if expected != actual:
        raise RuntimeError(f"{label} expected {expected!r}, received {actual!r}")


def booking_result(address, events, status, issues):
    reference = events[0]["bookingReference"]
    expected = {
        "bookingReference": reference,
        "status": status,
        "issues": issues,
        "timeline": sorted(
            map(normalize, events), key=lambda event: (event["eventDateTime"], event["eventId"])
        ),
    }
    http_status, result = request(address, f"/bookings/{reference}")
    check_equal(f"GET {reference}", 200, http_status)
    check_equal(reference, expected, result)
    return result


def show_booking(result, note=""):
    issues = ", ".join(result["issues"]) or "none"
    timeline = result["timeline"]
    print(f"{result['bookingReference']}{note} | {result['status']} | issues: {issues} | events: {len(timeline)}")
    for event in timeline:
        print(f"  {event['eventDateTime']}  {event['transportEventTypeCode']} "
              f"{event['eventClassifierCode']}  {event['eventId']}")


def database_counts(compose):
    rows = compose(
        "exec", "-T", "db", "psql", "-X", "-U", "shipments", "-d", "shipments",
        "-v", "ON_ERROR_STOP=1", "-At", "-F", "\t", "-c",
        "SELECT booking_reference, count(*) FROM shipment_events GROUP BY booking_reference",
        capture=True,
    )
    return {reference: int(count) for reference, count in (row.split("\t") for row in rows.splitlines())}


def demo(compose):
    compose("up", "--build", "--wait", "api")
    address = compose("port", "api", "8080", capture=True)
    starting_counts = database_counts(compose)
    check_equal("Initial PostgreSQL counts by booking", {}, starting_counts)
    print(f"Starting unique events in PostgreSQL: {sum(starting_counts.values())}")

    expected_counts = {}
    for name, status, issues in SCENARIOS:
        events = fixture(name)
        reference = events[0]["bookingReference"]
        for index, event in enumerate(events):
            response_status, response_event = request(address, "/events", event)
            check_equal(f"POST {event['eventId']}", 201, response_status)
            check_equal("New event", normalize(event), response_event)
            if name == "late-departure" and index == 0:
                result = booking_result(address, [event], "needs_review", ["missing_actual_departure"])
                check_equal("Counts while awaiting the late departure",
                            {**expected_counts, reference: 1}, database_counts(compose))
                show_booking(result, " (arrival received first)")

        result = booking_result(address, events, status, issues)
        show_booking(result, " (after departure received)" if name == "late-departure" else "")
        expected_counts[reference] = len(events)

    counts = database_counts(compose)
    check_equal("PostgreSQL counts by booking", expected_counts, counts)
    print(f"Accepted unique events in PostgreSQL: {sum(counts.values())}")

    events = fixture("estimated-arrival")
    replay = normalize(events[0])
    status, stored = request(address, "/events", replay)
    check_equal("Equivalent offset replay", 200, status)
    check_equal("Replayed event", replay, stored)
    booking_result(address, events, "in_transit", [])
    after_replay = database_counts(compose)
    check_equal("PostgreSQL counts after replay", counts, after_replay)
    print(f"Equivalent offset replay for {replay['bookingReference']}: HTTP {status}")
    print(f"Unique events in PostgreSQL after replay: {sum(after_replay.values())}")


def main():
    parser = argparse.ArgumentParser(description="Run checks against a disposable shipment database.")
    parser.add_argument("check", choices=["demo", "test"])
    args = parser.parse_args()

    project = f"shipment-{args.check}-{secrets.token_hex(6)}"
    environment = {**os.environ, "POSTGRES_PASSWORD": secrets.token_urlsafe(24)}

    def compose(*args, capture=False):
        result = subprocess.run(
            ["docker", "compose", "--project-name", project, "--file", "compose.yaml", *args],
            cwd=ROOT,
            env=environment,
            check=True,
            text=True,
            stdout=subprocess.PIPE if capture else sys.stderr,
        )
        return result.stdout.strip() if capture else None

    try:
        if args.check == "test":
            compose("run", "--rm", "--build", "tests")
        else:
            demo(compose)
    finally:
        compose("--profile", "test", "down", "--volumes", "--rmi", "local")


if __name__ == "__main__":
    main()
