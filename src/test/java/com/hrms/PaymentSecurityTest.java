package com.hrms;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hrms.booking.BookingService;
import com.hrms.messaging.Events;
import com.hrms.payment.PaymentService;
import com.hrms.promotion.CouponService;
import com.hrms.shared.*;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PaymentSecurityTest {
  @Test
  void invalidGatewaySignatureNeverTouchesTransactionOrDatabase() {
    Db db = mock(Db.class);
    Tx tx = mock(Tx.class);
    var payments =
        new PaymentService(
            db,
            tx,
            mock(BookingService.class),
            mock(CouponService.class),
            mock(Events.class),
            "unit-test-signing-secret");
    assertThatThrownBy(
            () ->
                payments.callback(
                    1, "ref", BigDecimal.ONE, true, Instant.now().getEpochSecond(), "invalid"))
        .isInstanceOf(Problem.class)
        .hasMessage("CALLBACK_SIGNATURE_INVALID");
    verifyNoInteractions(db, tx);
  }
}
