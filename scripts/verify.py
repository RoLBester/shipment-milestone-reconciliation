import argparse
from datetime import datetime, timezone
import http.client
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
from uuid import UUID


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


def parse_event_time(value):
    if value.endswith(("Z", "z")):
        value = value[:-1] + "+00:00"
    else:
        value = re.sub(r"([+-][0-9]{2})$", r"\1:00", value)
    # Match the API's truncation and give Python 3.9 six fractional digits.
    value = re.sub(
        r"\.([0-9]{1,9})(?=[+-])",
        lambda fraction: "." + fraction[1][:6].ljust(6, "0"),
        value,
        count=1,
    )
    instant = datetime.fromisoformat(value)
    if instant.tzinfo is None:
        raise ValueError("Event time requires a UTC offset.")
    return instant.astimezone(timezone.utc)


def parsed_event(event):
    return {**event, "eventDateTime": parse_event_time(event["eventDateTime"])}


def check_equal(label, expected, actual):
    if expected != actual:
        raise RuntimeError(f"{label} expected {expected!r}, received {actual!r}")


def post_event(address, event, expected_status):
    status, stored = request(address, "/events", event)
    check_equal(f"POST {event['eventId']}", expected_status, status)
    check_equal("Stored event", parsed_event(event), parsed_event(stored))
    return stored


def booking_result(address, events, status, issues):
    reference = events[0]["bookingReference"]
    expected = {
        "bookingReference": reference,
        "status": status,
        "issues": issues,
        "timeline": sorted(
            map(parsed_event, events),
            key=lambda event: (event["eventDateTime"], UUID(event["eventId"])),
        ),
    }
    http_status, result = request(address, f"/bookings/{reference}")
    check_equal(f"GET {reference}", 200, http_status)
    timeline = list(map(parsed_event, result["timeline"]))
    check_equal(reference, expected, {**result, "timeline": timeline})
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


def start_api(compose):
    compose("up", "--build", "--wait", "api")
    return compose("port", "api", "8080", capture=True)


def demo(compose):
    address = start_api(compose)
    starting_counts = database_counts(compose)
    check_equal("Initial PostgreSQL counts by booking", {}, starting_counts)
    print(f"Starting unique events in PostgreSQL: {sum(starting_counts.values())}")

    expected_counts = {}
    for name, status, issues in SCENARIOS:
        events = fixture(name)
        reference = events[0]["bookingReference"]
        for index, event in enumerate(events):
            post_event(address, event, 201)
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
    replay_time = parse_event_time(events[0]["eventDateTime"]).isoformat().replace("+00:00", "Z")
    replay = {**events[0], "eventDateTime": replay_time}
    post_event(address, replay, 200)
    booking_result(address, events, "in_transit", [])
    after_replay = database_counts(compose)
    check_equal("PostgreSQL counts after replay", counts, after_replay)
    print(f"Equivalent offset replay for {replay['bookingReference']}: HTTP 200")
    print(f"Unique events in PostgreSQL after replay: {sum(after_replay.values())}")


def verify_timestamps(compose):
    address = start_api(compose)
    departure = {
        "eventId": "41f54cab-1af0-4fcd-a230-000000000001",
        "bookingReference": "CHECK-TIMELINE",
        "eventDateTime": "2026-10-24T08:00:00Z",
        "transportEventTypeCode": "DEPA",
        "eventClassifierCode": "ACT",
    }
    arrival = {
        **departure,
        "eventId": "41f54cab-1af0-4fcd-a230-000000000002",
        "eventDateTime": "2026-10-24T08:00:00.000001Z",
        "transportEventTypeCode": "ARRI",
    }
    post_event(address, arrival, 201)
    show_booking(booking_result(address, [arrival], "needs_review", ["missing_actual_departure"]))
    check_equal("Rows before departure", {"CHECK-TIMELINE": 1}, database_counts(compose))
    post_event(address, departure, 201)
    show_booking(booking_result(address, [arrival, departure], "arrived", []))

    offset_event = {
        **departure,
        "eventId": "41f54cab-1af0-4fcd-a230-000000000003",
        "bookingReference": "CHECK-OFFSET",
        "eventDateTime": "2026-10-24T08:00:00.125Z",
    }
    post_event(address, offset_event, 201)
    for equivalent in ("2026-10-24T08:00:00.125000Z", "2026-10-24T16:00:00.125+08:00"):
        replay = {**offset_event, "eventDateTime": equivalent}
        post_event(address, replay, 200)
        booking_result(address, [replay], "in_transit", [])
    expected_counts = {"CHECK-TIMELINE": 2, "CHECK-OFFSET": 1}
    check_equal("Rows after equivalent replays", expected_counts, database_counts(compose))
    print("Both equivalent timestamps returned HTTP 200. PostgreSQL stored one event for CHECK-OFFSET.")

    precision_event = {
        **departure,
        "eventId": "41f54cab-1af0-4fcd-a230-000000000004",
        "bookingReference": "CHECK-PRECISION",
        "eventDateTime": "2026-10-24T08:00:00.123456900Z",
    }
    stored = post_event(address, precision_event, 201)
    check_equal("Timestamp truncated to microseconds", "2026-10-24T08:00:00.123456Z", stored["eventDateTime"])
    show_booking(booking_result(address, [precision_event], "in_transit", []))
    check_equal("Rows after precision check", {**expected_counts, "CHECK-PRECISION": 1}, database_counts(compose))


def main():
    parser = argparse.ArgumentParser(description="Run checks against a disposable shipment database.")
    parser.add_argument("check", choices=["demo", "test", "timestamps"])
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
        elif args.check == "timestamps":
            verify_timestamps(compose)
        else:
            demo(compose)
    finally:
        compose("--profile", "test", "down", "--volumes", "--rmi", "local")


if __name__ == "__main__":
    main()
