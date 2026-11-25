# Shipment Milestone Reconciliation

A Spring Boot API for shipping operations. It records vessel departure and destination-port arrival updates for one direct sea leg per booking. It rebuilds the timeline by event time and flags actual milestones that need review.

## Run the demo

Run this command from the repository root with Docker Compose and Python 3.9 or newer.

```sh
python3 scripts/verify.py demo
```

The command starts the API with an empty PostgreSQL database, posts the [fictional fixtures](fixtures), checks each timeline, and queries PostgreSQL for row counts by booking. It removes its containers and database volume when it finishes. The [sample output](examples/demo-output.txt) shows the timelines and counts.

## API

| Request | Result |
| --- | --- |
| `POST /events` | Accepts one JSON object. Returns `201` for a new event and `200` for a replay of the same normalized event. Returns `409` if the ID is already used by a different event, preserving the stored row. Invalid event data returns `400`. |
| `GET /bookings/{bookingReference}` | Returns the booking reference, status, issue codes and timeline. Events are ordered by occurrence time and then UUID. A booking with no events returns `404`. |

All five request fields are required strings. Booking references are case-sensitive, start with a letter or digit, and allow up to 100 ASCII letters, digits and the characters `._~-`. Event IDs use the hyphenated UUID format. Event times need an ISO 8601 UTC offset or `Z` and must fall in UTC years 0001 through 9999. The service converts them to UTC and truncates to microseconds before storage and replay comparison.

PostgreSQL's primary key on `event_id` settles concurrent submissions. When the insert skips an existing ID, a second statement reads the committed row for comparison. The booking query reads the stored events, so a departure delivered late can correct the status immediately.

### DCSA field mapping

The fixtures follow the vessel transport examples in DCSA's `Load Test Data` request and use the transport event definitions from the 2024 Q1 Information Model. In the direct sea-leg model, `ARRI` means the vessel reached the destination port. [DCSA sample collection at the reviewed commit](https://github.com/dcsaorg/DCSA-TNT/blob/af619ff6abefe1bf6d668cf88477c1c2ee20cd08/postman_collection.json#L18) · [DCSA Information Model 2024 Q1](https://reference.dcsa.org/files/content/standards/guidelines/information-model-2024-q1/dcsa-information-model-2024-q1.pdf)

| DCSA example field | Service field |
| --- | --- |
| `metadata.eventID` | `eventId` |
| `payload.relatedDocumentReferences` entry with type `BKG` | Its `value` becomes `bookingReference` |
| `payload.eventDateTime` | `eventDateTime`, the time the milestone occurred or is scheduled to occur |
| `payload.transportEventTypeCode` | `transportEventTypeCode` with `DEPA` for departed or `ARRI` for arrived |
| `payload.eventClassifierCode` | `eventClassifierCode` with `ACT` for actual, `PLN` for planned or `EST` for estimated |

### Read the results

Planned and estimated events stay visible. Actual events determine progress. Conflicting actuals for one milestone appear first in the issue list, followed by an actual arrival without an actual departure, then any actual arrival at or before an actual departure.

| Fixture booking | Status | Issue | Unique events |
| --- | --- | --- | ---: |
| `DEMO-PLANNED` | `awaiting_departure` | — | 1 |
| `DEMO-TRANSIT` | `in_transit` | — | 2 |
| `DEMO-ARRIVED` | `arrived` | — | 2 |
| `DEMO-LATE`, arrival received first | `needs_review` | `missing_actual_departure` | 1 |
| `DEMO-LATE`, after departure received | `arrived` | — | 2 |
| `DEMO-CHRONOLOGY` | `needs_review` | `inconsistent_chronology` | 2 |
| `DEMO-CONFLICT` | `needs_review` | `conflicting_actual_events` | 2 |

PostgreSQL stored 11 unique events across six bookings. The demo resubmitted the `DEMO-TRANSIT` departure using a different UTC offset. The API returned `200`, and the count stayed at 11. The late departure moved ahead of its arrival in the timeline and cleared the review issue.

## Run the API locally

For a fresh local stack, set a password and keep it for subsequent starts of the same database volume. The API uses an available loopback port.

```sh
export POSTGRES_PASSWORD="$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')"
docker compose up --build --wait
api_url="http://$(docker compose port api 8080)"
event="$(python3 -c 'import json; print(json.dumps(json.load(open("fixtures/planned-arrival.json"))[0]))')"
curl -i -H 'Content-Type: application/json' --data "$event" "$api_url/events"
curl -sS "$api_url/bookings/DEMO-PLANNED"
docker compose down
```

## Verify

```sh
python3 scripts/verify.py test
demo_result="$(mktemp)"
python3 scripts/verify.py demo > "$demo_result"
diff -u examples/demo-output.txt "$demo_result"
```

The JUnit suite checks reconciliation rules and HTTP behavior against PostgreSQL, including concurrent replays and conflicts, time normalization, malformed requests, missing bookings and both steps of the late-departure case.

The build uses Java 25 LTS, Maven 3.10.0, Spring Boot 4.2.0 and PostgreSQL 18.7. The Spring Boot parent fixes dependency and plugin versions, including JUnit 6.1.3 and PostgreSQL JDBC 42.7.14. The container images use version tags and immutable digests for Linux arm64 and amd64. [Spring Boot requirements](https://docs.spring.io/spring-boot/4.2/system-requirements.html) · [PostgreSQL support policy](https://www.postgresql.org/support/versioning/)
