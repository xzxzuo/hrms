package com.hrms.booking;
import com.hrms.shared.*;import com.hrms.identity.Actor;import com.hrms.observability.Audit;import java.util.*;import jakarta.validation.*;import jakarta.validation.constraints.*;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api") public class BookingController {
 private final Db db;private final BookingService bookings;private final Actor actor;private final Audit audit;
 public BookingController(Db db,BookingService bookings,Actor actor,Audit audit){this.db=db;this.bookings=bookings;this.actor=actor;this.audit=audit;}
 public record Create(@NotBlank @Size(max=36) String quoteId,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,80}") String clientRequestId,Long couponId){}
 @PostMapping("/bookings") public Map<String,Object> create(@Valid @RequestBody Create c){return bookings.create(actor.id(),c.clientRequestId(),c.quoteId(),c.couponId());}
 @GetMapping("/bookings") public List<Map<String,Object>> mine(){return db.list("SELECT * FROM bookings WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 100",actor.id());}
 @GetMapping("/bookings/{id}") public Map<String,Object> get(@PathVariable long id){var b=bookings.get(id);actor.owner(Db.id(b,"user_id"));return b;}
 @PostMapping("/bookings/{id}/cancel") public Map<String,Object> cancel(@PathVariable long id){get(id);return bookings.cancel(id);}
 @PostMapping("/admin/bookings/{id}/{action}") public Map<String,Object> admin(@PathVariable long id,@PathVariable String action){var b=bookings.get(id);actor.hotel(Db.id(b,"hotel_id"));Map<String,Object> result=switch(action){case "cancel"->bookings.cancel(id);case "check-in"->bookings.checkIn(id);case "check-out"->bookings.checkOut(id);default->throw new Problem(400,"INVALID_ACTION");};audit.log(actor.id(),"ADMIN_BOOKING_"+action,Long.toString(id));return result;}
}
