CREATE TABLE IF NOT EXISTS shipment_events (
    event_id uuid PRIMARY KEY,
    booking_reference varchar(100) NOT NULL,
    event_date_time timestamptz(6) NOT NULL,
    transport_event_type_code varchar(4) NOT NULL CHECK (transport_event_type_code IN ('DEPA', 'ARRI')),
    event_classifier_code varchar(3) NOT NULL CHECK (event_classifier_code IN ('ACT', 'PLN', 'EST'))
);

CREATE INDEX IF NOT EXISTS shipment_events_booking_timeline_idx
    ON shipment_events (booking_reference, event_date_time, event_id);
