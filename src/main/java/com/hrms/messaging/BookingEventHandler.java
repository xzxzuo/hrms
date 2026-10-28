package com.hrms.messaging;
import com.hrms.shared.Db;import com.hrms.booking.BookingService;import com.hrms.hotel.HotelCache;import org.springframework.stereotype.Component;
@Component public class BookingEventHandler implements DomainEventHandler {
 private final Db db;private final BookingService bookings;private final HotelCache cache;
 public BookingEventHandler(Db db,BookingService bookings,HotelCache cache){this.db=db;this.bookings=bookings;this.cache=cache;}
 public boolean supports(String type){return type.startsWith("BOOKING_")||type.startsWith("PAYMENT_")||type.startsWith("REFUND_")||type.equals("CACHE_INVALIDATE");}
 public void handle(String event,String type,String aggregate){long id=Long.parseLong(aggregate);if(type.equals("CACHE_INVALIDATE")){cache.invalidate(id);return;}if(type.equals("BOOKING_CREATED")){bookings.expire(id);return;}db.exec("INSERT INTO notifications(event_id,booking_id,kind) VALUES(?,?,?)",event,id,type);}
}
