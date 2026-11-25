package io.github.rolbester.shipment;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

record BookingTimeline(String bookingReference, Status status, List<Issue> issues, List<MilestoneEvent> timeline) {
    enum Status {
        @JsonProperty("awaiting_departure") AWAITING_DEPARTURE,
        @JsonProperty("in_transit") IN_TRANSIT,
        @JsonProperty("arrived") ARRIVED,
        @JsonProperty("needs_review") NEEDS_REVIEW
    }

    enum Issue {
        @JsonProperty("conflicting_actual_events") CONFLICTING_ACTUAL_EVENTS,
        @JsonProperty("missing_actual_departure") MISSING_ACTUAL_DEPARTURE,
        @JsonProperty("inconsistent_chronology") INCONSISTENT_CHRONOLOGY
    }
}
