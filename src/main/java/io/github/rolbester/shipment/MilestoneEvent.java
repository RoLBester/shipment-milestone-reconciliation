package io.github.rolbester.shipment;

import java.time.Instant;
import java.util.UUID;

record MilestoneEvent(
        UUID eventId,
        String bookingReference,
        Instant eventDateTime,
        TransportEventType transportEventTypeCode,
        Classifier eventClassifierCode) {

    enum TransportEventType { DEPA, ARRI }

    enum Classifier { ACT, PLN, EST }
}
