package com.hrms.pricing;
import com.hrms.identity.Actor;import jakarta.validation.*;import jakarta.validation.constraints.*;import java.time.LocalDate;import java.util.Map;import org.springframework.web.bind.annotation.*;
@RestController public class PricingController {private final PricingService pricing;private final Actor actor;public PricingController(PricingService pricing,Actor actor){this.pricing=pricing;this.actor=actor;}
 public record Quote(@Positive long roomTypeId,@NotNull LocalDate checkIn,@NotNull LocalDate checkOut,@Min(1) @Max(10) int quantity){}
 @PostMapping("/api/quotes") public Map<String,Object> quote(@Valid @RequestBody Quote q){return pricing.quote(actor.id(),q.roomTypeId(),q.checkIn(),q.checkOut(),q.quantity());}
}
