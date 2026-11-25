package io.github.rolbester.shipment;

import io.github.rolbester.shipment.BookingTimeline.Issue;
import io.github.rolbester.shipment.BookingTimeline.Status;
import io.github.rolbester.shipment.MilestoneEvent.Classifier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class TimelineReconciler {
    private TimelineReconciler() {
    }

    static BookingTimeline reconcile(String bookingReference, List<MilestoneEvent> timeline) {
        List<Instant> departures = new ArrayList<>();
        List<Instant> arrivals = new ArrayList<>();
        for (MilestoneEvent event : timeline) {
            if (event.eventClassifierCode() == Classifier.ACT) {
                switch (event.transportEventTypeCode()) {
                    case DEPA -> departures.add(event.eventDateTime());
                    case ARRI -> arrivals.add(event.eventDateTime());
                }
            }
        }

        boolean departed = !departures.isEmpty();
        boolean arrived = !arrivals.isEmpty();
        List<Issue> issues = new ArrayList<>();
        if (departures.size() > 1 || arrivals.size() > 1) {
            issues.add(Issue.CONFLICTING_ACTUAL_EVENTS);
        }
        if (arrived && !departed) {
            issues.add(Issue.MISSING_ACTUAL_DEPARTURE);
        }
        if (arrived && departed && !Collections.min(arrivals).isAfter(Collections.max(departures))) {
            issues.add(Issue.INCONSISTENT_CHRONOLOGY);
        }

        Status status;
        if (!issues.isEmpty()) {
            status = Status.NEEDS_REVIEW;
        } else if (arrived) {
            status = Status.ARRIVED;
        } else if (departed) {
            status = Status.IN_TRANSIT;
        } else {
            status = Status.AWAITING_DEPARTURE;
        }
        return new BookingTimeline(bookingReference, status, List.copyOf(issues), List.copyOf(timeline));
    }
}
