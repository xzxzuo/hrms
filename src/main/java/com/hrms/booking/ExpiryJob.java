package com.hrms.booking;
import com.hrms.shared.Db;import org.springframework.stereotype.Component;import org.springframework.scheduling.annotation.Scheduled;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.slf4j.LoggerFactory;
@Component @ConditionalOnProperty(name="hrms.jobs-enabled",havingValue="true",matchIfMissing=true) public class ExpiryJob {
 private final Db db;private final BookingService bookings;public ExpiryJob(Db db,BookingService bookings){this.db=db;this.bookings=bookings;}
 @Scheduled(fixedDelay=5000) public void scan(){for(var b:db.list("SELECT id FROM bookings WHERE status='PENDING_PAYMENT' AND expires_at<=UTC_TIMESTAMP(6) ORDER BY expires_at,id LIMIT 100")){try{bookings.expire(Db.id(b,"id"));}catch(Exception e){LoggerFactory.getLogger(ExpiryJob.class).warn("Expiry failed bookingId={}",b.get("id"));}}}
}
