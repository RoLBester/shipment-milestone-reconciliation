package io.github.rolbester.shipment;

import io.github.rolbester.shipment.BookingTimeline.Issue;
import io.github.rolbester.shipment.BookingTimeline.Status;
import io.github.rolbester.shipment.MilestoneEvent.Classifier;
import io.github.rolbester.shipment.MilestoneEvent.TransportEventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.github.rolbester.shipment.MilestoneEvent.Classifier.ACT;
import static io.github.rolbester.shipment.MilestoneEvent.Classifier.EST;
import static io.github.rolbester.shipment.MilestoneEvent.Classifier.PLN;
import static io.github.rolbester.shipment.MilestoneEvent.TransportEventType.ARRI;
import static io.github.rolbester.shipment.MilestoneEvent.TransportEventType.DEPA;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TimelineReconcilerTest {
    private static final String BOOKING = "TEST-SEA-LEG";
    private static final Instant DEPARTURE = Instant.parse("2026-10-24T08:00:00Z");
    private static final Instant ARRIVAL = Instant.parse("2026-10-25T08:00:00Z");

    @Test
    void plannedArrivalAloneIsAwaitingDeparture() {
        var plannedArrival = event(ARRI, PLN, ARRIVAL);
        var booking = reconcile(plannedArrival);

        assertEquals(Status.AWAITING_DEPARTURE, booking.status());
        assertEquals(List.of(), booking.issues());
        assertEquals(List.of(plannedArrival), booking.timeline());
    }

    @Test
    void estimatesRemainVisibleWithoutConfirmingArrival() {
        var departure = event(DEPA, ACT, DEPARTURE);
        var estimate = event(ARRI, EST, ARRIVAL);
        var booking = reconcile(departure, estimate);

        assertEquals(Status.IN_TRANSIT, booking.status());
        assertEquals(List.of(), booking.issues());
        assertEquals(List.of(departure, estimate), booking.timeline());
    }

    @Test
    void laterActualArrivalConfirmsArrival() {
        var booking = reconcile(event(DEPA, ACT, DEPARTURE), event(ARRI, ACT, ARRIVAL));

        assertEquals(Status.ARRIVED, booking.status());
        assertEquals(List.of(), booking.issues());
    }

    @Test
    void plannedDepartureDoesNotResolveAnActualArrival() {
        var booking = reconcile(event(DEPA, PLN, DEPARTURE), event(ARRI, ACT, ARRIVAL));

        assertEquals(Status.NEEDS_REVIEW, booking.status());
        assertEquals(List.of(Issue.MISSING_ACTUAL_DEPARTURE), booking.issues());
    }

    @Test
    void actualArrivalBeforeActualDepartureNeedsReview() {
        var booking = reconcile(event(ARRI, ACT, DEPARTURE), event(DEPA, ACT, ARRIVAL));

        assertEquals(Status.NEEDS_REVIEW, booking.status());
        assertEquals(List.of(Issue.INCONSISTENT_CHRONOLOGY), booking.issues());
    }

    @Test
    void actualArrivalAtDepartureTimeNeedsReview() {
        var booking = reconcile(event(DEPA, ACT, DEPARTURE), event(ARRI, ACT, DEPARTURE));

        assertEquals(Status.NEEDS_REVIEW, booking.status());
        assertEquals(List.of(Issue.INCONSISTENT_CHRONOLOGY), booking.issues());
    }

    @Test
    void conflictingArrivalsAreReportedBeforeMissingDeparture() {
        var booking = reconcile(event(ARRI, ACT, ARRIVAL), event(ARRI, ACT, ARRIVAL));

        assertEquals(Status.NEEDS_REVIEW, booking.status());
        assertEquals(List.of(Issue.CONFLICTING_ACTUAL_EVENTS, Issue.MISSING_ACTUAL_DEPARTURE), booking.issues());
    }

    @Test
    void conflictingDeparturesAreReportedBeforeInconsistentChronology() {
        var booking = reconcile(
                event(DEPA, ACT, DEPARTURE),
                event(ARRI, ACT, ARRIVAL),
                event(DEPA, ACT, ARRIVAL.plusSeconds(1)));

        assertEquals(Status.NEEDS_REVIEW, booking.status());
        assertEquals(List.of(Issue.CONFLICTING_ACTUAL_EVENTS, Issue.INCONSISTENT_CHRONOLOGY), booking.issues());
    }

    private static BookingTimeline reconcile(MilestoneEvent... events) {
        return TimelineReconciler.reconcile(BOOKING, List.of(events));
    }

    private static MilestoneEvent event(TransportEventType type, Classifier classifier, Instant time) {
        return new MilestoneEvent(UUID.randomUUID(), BOOKING, time, type, classifier);
    }
}
