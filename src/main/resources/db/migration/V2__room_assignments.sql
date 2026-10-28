CREATE TABLE booking_room_assignments (
 booking_id BIGINT NOT NULL,room_id BIGINT NOT NULL,PRIMARY KEY(booking_id,room_id),FOREIGN KEY(booking_id) REFERENCES bookings(id),FOREIGN KEY(room_id) REFERENCES physical_rooms(id)
);
