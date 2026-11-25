package io.github.rolbester.shipment;

import io.github.rolbester.shipment.MilestoneEvent.Classifier;
import io.github.rolbester.shipment.MilestoneEvent.TransportEventType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
class EventRepository {
    enum WriteResult { INSERTED, REPLAY, CONFLICT }

    private static final RowMapper<MilestoneEvent> EVENT_ROW = (row, rowNumber) -> new MilestoneEvent(
            row.getObject("event_id", UUID.class),
            row.getString("booking_reference"),
            row.getObject("event_date_time", OffsetDateTime.class).toInstant(),
            TransportEventType.valueOf(row.getString("transport_event_type_code")),
            Classifier.valueOf(row.getString("event_classifier_code")));

    private final JdbcClient jdbc;

    EventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WriteResult store(MilestoneEvent event) {
        int inserted = jdbc.sql("""
                        INSERT INTO shipment_events
                            (event_id, booking_reference, event_date_time, transport_event_type_code, event_classifier_code)
                        VALUES (:id, :booking, :time, :type, :classifier)
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("id", event.eventId())
                .param("booking", event.bookingReference())
                .param("time", OffsetDateTime.ofInstant(event.eventDateTime(), ZoneOffset.UTC))
                .param("type", event.transportEventTypeCode().name())
                .param("classifier", event.eventClassifierCode().name())
                .update();
        if (inserted == 1) {
            return WriteResult.INSERTED;
        }

        // The next statement can see the committed row from a concurrent insert.
        MilestoneEvent stored = jdbc.sql("SELECT * FROM shipment_events WHERE event_id = :id")
                .param("id", event.eventId())
                .query(EVENT_ROW)
                .single();
        return stored.equals(event) ? WriteResult.REPLAY : WriteResult.CONFLICT;
    }

    List<MilestoneEvent> findByBooking(String bookingReference) {
        return jdbc.sql("""
                        SELECT * FROM shipment_events
                        WHERE booking_reference = :booking
                        ORDER BY event_date_time, event_id
                        """)
                .param("booking", bookingReference)
                .query(EVENT_ROW)
                .list();
    }
}
