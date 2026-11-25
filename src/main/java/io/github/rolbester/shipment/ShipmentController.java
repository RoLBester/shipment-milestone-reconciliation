package io.github.rolbester.shipment;

import io.github.rolbester.shipment.MilestoneEvent.Classifier;
import io.github.rolbester.shipment.MilestoneEvent.TransportEventType;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
class ShipmentController {
    private static final Pattern BOOKING_REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._~-]{0,99}");
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private static final Instant FIRST_EVENT_TIME = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant EVENT_TIME_LIMIT = Instant.parse("+10000-01-01T00:00:00Z");

    private final EventRepository events;

    ShipmentController(EventRepository events) {
        this.events = events;
    }

    @PostMapping(path = "/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MilestoneEvent> postEvent(@RequestBody ObjectNode request) {
        MilestoneEvent event = readEvent(request);
        return switch (events.store(event)) {
            case INSERTED -> ResponseEntity.status(HttpStatus.CREATED).body(event);
            case REPLAY -> ResponseEntity.ok(event);
            case CONFLICT -> throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This eventId already belongs to a different event.");
        };
    }

    @GetMapping("/bookings/{bookingReference}")
    BookingTimeline getBooking(@PathVariable String bookingReference) {
        List<MilestoneEvent> timeline = events.findByBooking(bookingReference);
        if (timeline.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking has no recorded events.");
        }
        return TimelineReconciler.reconcile(bookingReference, timeline);
    }

    private static MilestoneEvent readEvent(ObjectNode request) {
        String id = requiredText(request, "eventId");
        if (!UUID_TEXT.matcher(id).matches()) {
            throw invalid("eventId must be a UUID.");
        }
        String booking = requiredText(request, "bookingReference");
        if (!BOOKING_REFERENCE.matcher(booking).matches()) {
            throw invalid("bookingReference must be a URL-safe reference of up to 100 characters starting with a letter or digit.");
        }
        Instant time = readTime(requiredText(request, "eventDateTime"));
        String type = requiredText(request, "transportEventTypeCode");
        String classifier = requiredText(request, "eventClassifierCode");
        TransportEventType eventType;
        Classifier eventClassifier;
        try {
            eventType = TransportEventType.valueOf(type);
            eventClassifier = Classifier.valueOf(classifier);
        } catch (IllegalArgumentException ex) {
            throw invalid("Supported transport codes are DEPA and ARRI. Supported classifiers are ACT, PLN and EST.");
        }
        if (request.size() != 5) {
            throw invalid("The event contains an unexpected field.");
        }
        return new MilestoneEvent(UUID.fromString(id), booking, time, eventType, eventClassifier);
    }

    private static String requiredText(ObjectNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw invalid(field + " must be a non-blank string.");
        }
        return value.stringValue();
    }

    private static Instant readTime(String value) {
        Instant time;
        try {
            time = OffsetDateTime.parse(value).toInstant().truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException ex) {
            throw invalid("eventDateTime must be ISO 8601 with a UTC offset.");
        }
        if (time.isBefore(FIRST_EVENT_TIME) || !time.isBefore(EVENT_TIME_LIMIT)) {
            throw invalid("eventDateTime must be in UTC years 0001 through 9999.");
        }
        return time;
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
