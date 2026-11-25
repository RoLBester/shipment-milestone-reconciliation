package io.github.rolbester.shipment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShipmentHttpTest {
    private static final String DEPARTURE = "2026-10-24T08:00:00Z";
    private static final String ARRIVAL = "2026-10-25T08:00:00Z";
    private static final String EVENTS = "/events";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcClient jdbc;

    @Test
    void returns201OnInsertAnd200OnIdenticalReplay() throws Exception {
        String booking = bookingReference();
        ObjectNode departure = event(booking, DEPARTURE, "DEPA", "ACT");

        assertEquals(201, post(departure).statusCode());
        assertEquals(200, post(departure).statusCode());

        JsonNode timeline = booking(booking);
        assertEquals("in_transit", timeline.get("status").asString());
        assertEquals(1, timeline.get("timeline").size());
        assertEquals(1, storedEvents(booking));
    }

    @Test
    void normalizesOffsetsAndTruncatesToPostgresPrecision() throws Exception {
        String booking = bookingReference();
        ObjectNode departure = event(booking, "2026-10-24T16:00:00.123456900+08:00", "DEPA", "ACT");
        var insert = post(departure);
        assertEquals(201, insert.statusCode());
        assertEquals("2026-10-24T08:00:00.123456Z", json.readTree(insert.body()).get("eventDateTime").asString());

        var equivalentOffset = departure.deepCopy().put("eventDateTime", "2026-10-24T08:00:00.123456900Z");
        assertEquals(200, post(equivalentOffset).statusCode());
        var sameMicrosecond = departure.deepCopy().put("eventDateTime", "2026-10-24T08:00:00.123456100Z");
        assertEquals(200, post(sameMicrosecond).statusCode());

        assertEquals(json.readTree(insert.body()), booking(booking).get("timeline").get(0));
        assertEquals(1, storedEvents(booking));
    }

    @Test
    void simultaneousIdenticalSubmissionsStoreOneEvent() throws Exception {
        String booking = bookingReference();
        ObjectNode departure = event(booking, DEPARTURE, "DEPA", "ACT");

        var replies = postTogether(departure, departure);

        assertEquals(List.of(200, 201), replies.stream().map(HttpResponse::statusCode).sorted().toList());
        assertEquals(replies.getFirst().body(), replies.getLast().body());
        assertEquals(1, storedEvents(booking));
    }

    @Test
    void simultaneousConflictingSubmissionsPreserveTheWinner() throws Exception {
        String booking = bookingReference();
        ObjectNode departure = event(booking, DEPARTURE, "DEPA", "ACT");
        ObjectNode conflict = departure.deepCopy().put("eventDateTime", ARRIVAL);

        var replies = postTogether(departure, conflict);

        assertEquals(List.of(201, 409), replies.stream().map(HttpResponse::statusCode).sorted().toList());
        var winner = replies.stream().filter(reply -> reply.statusCode() == 201).findFirst().orElseThrow();
        assertEquals(json.readTree(winner.body()), booking(booking).get("timeline").get(0));
        assertEquals(1, storedEvents(booking));
    }

    @Test
    void reuseOfTheSameIdCannotChangeAnyStoredField() throws Exception {
        String booking = bookingReference();
        String otherBooking = bookingReference();
        ObjectNode departure = event(booking, DEPARTURE, "DEPA", "ACT");
        var insert = post(departure);
        assertEquals(201, insert.statusCode());

        for (ObjectNode conflict : List.of(
                departure.deepCopy().put("bookingReference", otherBooking),
                departure.deepCopy().put("eventDateTime", ARRIVAL),
                departure.deepCopy().put("transportEventTypeCode", "ARRI"),
                departure.deepCopy().put("eventClassifierCode", "EST"))) {
            assertEquals(409, post(conflict).statusCode());
        }

        assertEquals(json.readTree(insert.body()), booking(booking).get("timeline").get(0));
        assertEquals(1, storedEvents(booking));
        assertEquals(0, storedEvents(otherBooking));
    }

    @Test
    void lateDepartureReordersTheTimelineAndClearsTheIssue() throws Exception {
        String booking = bookingReference();
        ObjectNode arrival = event(booking, ARRIVAL, "ARRI", "ACT");
        ObjectNode departure = event(booking, DEPARTURE, "DEPA", "ACT");

        assertEquals(201, post(arrival).statusCode());
        JsonNode before = booking(booking);
        assertEquals("needs_review", before.get("status").asString());
        assertEquals(json.readTree("[\"missing_actual_departure\"]"), before.get("issues"));
        assertEquals(json.createArrayNode().add(arrival), before.get("timeline"));
        assertEquals(1, storedEvents(booking));

        assertEquals(201, post(departure).statusCode());
        JsonNode after = booking(booking);
        assertEquals("arrived", after.get("status").asString());
        assertEquals(json.createArrayNode(), after.get("issues"));
        assertEquals(json.createArrayNode().add(departure).add(arrival), after.get("timeline"));
        assertEquals(2, storedEvents(booking));
    }

    @Test
    void ordersTiedTimesByUuidAndReportsBothReviewIssues() throws Exception {
        String booking = bookingReference();
        ObjectNode first = event(booking, ARRIVAL, "ARRI", "ACT")
                .put("eventId", "18cca68a-a65a-4af6-9b08-bc00bbfbd001");
        ObjectNode last = event(booking, ARRIVAL, "ARRI", "ACT")
                .put("eventId", "c043d934-d46d-4897-bafe-e9fbd119d002");

        assertEquals(201, post(last).statusCode());
        assertEquals(201, post(first).statusCode());
        JsonNode result = booking(booking);
        assertEquals("needs_review", result.get("status").asString());
        assertEquals(json.readTree("[\"conflicting_actual_events\",\"missing_actual_departure\"]"), result.get("issues"));
        assertEquals(json.createArrayNode().add(first).add(last), result.get("timeline"));
    }

    @Test
    void returns404ForAnUnknownBooking() throws Exception {
        assertEquals(404, getBooking(bookingReference()).statusCode());
    }

    @ParameterizedTest
    @MethodSource("invalidFields")
    void rejectsMissingOrInvalidFields(String field, String replacement) throws Exception {
        String booking = bookingReference();
        ObjectNode request = event(booking, DEPARTURE, "DEPA", "ACT");
        if (replacement == null) {
            request.remove(field);
        } else {
            request.set(field, json.readTree(replacement));
        }

        assertEquals(400, post(request).statusCode());
        assertEquals(0, storedEvents(booking));
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(
                Arguments.of("eventId", null),
                Arguments.of("eventId", "\"1-2-3-4-5\""),
                Arguments.of("bookingReference", null),
                Arguments.of("bookingReference", "null"),
                Arguments.of("bookingReference", "\"\""),
                Arguments.of("bookingReference", "123"),
                Arguments.of("bookingReference", "\"SHIP/REF\""),
                Arguments.of("eventDateTime", null),
                Arguments.of("eventDateTime", "1792828800"),
                Arguments.of("eventDateTime", "\"2026-10-24T08:00:00\""),
                Arguments.of("eventDateTime", "\"2026-02-30T08:00:00Z\""),
                Arguments.of("eventDateTime", "\"+300000-01-01T08:00:00Z\""),
                Arguments.of("transportEventTypeCode", null),
                Arguments.of("transportEventTypeCode", "\"LOAD\""),
                Arguments.of("eventClassifierCode", null),
                Arguments.of("eventClassifierCode", "\"act\""),
                Arguments.of("eventClassifierCode", "0"),
                Arguments.of("unexpected", "true"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "null", "[]"})
    void rejectsMalformedBodies(String body) throws Exception {
        assertEquals(400, request(EVENTS, body).statusCode());
    }

    @Test
    void rejectsDuplicateJsonKeysAndMultipleEventsInOneBody() throws Exception {
        String booking = bookingReference();
        String departure = event(booking, DEPARTURE, "DEPA", "ACT").toString();
        String duplicateKey = "{\"eventClassifierCode\":\"PLN\"," + departure.substring(1);

        assertEquals(400, request(EVENTS, duplicateKey).statusCode());
        assertEquals(400, request(EVENTS, departure + departure).statusCode());
        assertEquals(0, storedEvents(booking));
    }

    private List<HttpResponse<String>> postTogether(ObjectNode first, ObjectNode second) throws Exception {
        var ready = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstReply = executor.submit(() -> {
                ready.await(5, TimeUnit.SECONDS);
                return post(first);
            });
            var secondReply = executor.submit(() -> {
                ready.await(5, TimeUnit.SECONDS);
                return post(second);
            });
            return List.of(firstReply.get(20, TimeUnit.SECONDS), secondReply.get(20, TimeUnit.SECONDS));
        }
    }

    private static String bookingReference() {
        return "TEST-" + UUID.randomUUID();
    }

    private ObjectNode event(String booking, String time, String type, String classifier) {
        return json.createObjectNode()
                .put("eventId", UUID.randomUUID().toString())
                .put("bookingReference", booking)
                .put("eventDateTime", time)
                .put("transportEventTypeCode", type)
                .put("eventClassifierCode", classifier);
    }

    private int storedEvents(String booking) {
        return jdbc.sql("SELECT count(*) FROM shipment_events WHERE booking_reference = :booking")
                .param("booking", booking)
                .query(Integer.class)
                .single();
    }

    private JsonNode booking(String booking) throws Exception {
        var response = getBooking(booking);
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private HttpResponse<String> getBooking(String booking) throws IOException, InterruptedException {
        return request("/bookings/" + booking, null);
    }

    private HttpResponse<String> post(ObjectNode event) throws IOException, InterruptedException {
        return request(EVENTS, event.toString());
    }

    private HttpResponse<String> request(String path, String body) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (body != null) {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
