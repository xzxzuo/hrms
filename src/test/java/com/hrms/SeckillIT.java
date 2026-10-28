package com.hrms;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.hrms.messaging.*;
import com.hrms.seckill.SeckillService;
import com.hrms.shared.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

class SeckillIT extends TestSupport {
  @Autowired SeckillService seckill;
  @Autowired OutboxPublisher publisher;
  @Autowired MessageProcessor processor;
  @Autowired StringRedisTemplate redis;
  @Autowired RabbitListenerEndpointRegistry listeners;

  long campaign(Fixture f, int quota) {
    return db.insert(
        "INSERT INTO seckill_campaigns(hotel_id,room_type_id,check_in,check_out,starts_at,ends_at,price,quota) VALUES(?,?,?,?,DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),99,?)",
        f.hotel(),
        f.room(),
        f.in(),
        f.in().plusDays(f.nights()),
        quota);
  }

  void process(long campaign, int requests) {
    publisher.drain(5000);
    await()
        .atMost(Duration.ofSeconds(40))
        .until(
            () ->
                db.count(
                        "SELECT COUNT(*) FROM seckill_requests WHERE campaign_id=? AND status<>'QUEUED'",
                        campaign)
                    == requests);
    publisher.drain(5000);
  }

  @Test
  void thousandConcurrentUsersForHundredQuotas() throws Exception {
    var f = fixture(100, 3);
    long campaign = campaign(f, 100);
    // Exercise first-access reconstruction under the full burst as well.
    var users = new ArrayList<Long>();
    for (int i = 0; i < 1000; i++) users.add(user());
    long begin = System.nanoTime();
    var results =
        concurrent(
            1000,
            i -> {
              try {
                return (String) seckill.request(campaign, users.get(i)).get("id");
              } catch (Problem e) {
                assertThat(e.code)
                    .isIn("CAMPAIGN_SOLD_OUT", "ADMISSION_BUSY", "ADMISSION_UNAVAILABLE");
                return e.code;
              }
            });
    long admitted = db.count("SELECT COUNT(*) FROM seckill_requests WHERE campaign_id=?", campaign);
    assertThat(admitted).isEqualTo(100);
    process(campaign, 100);
    assertThat(
            db.count(
                "SELECT COUNT(*) FROM seckill_requests WHERE campaign_id=? AND status='SUCCESS'",
                campaign))
        .isEqualTo(100);
    assertThat(db.count("SELECT quota_used FROM seckill_campaigns WHERE id=?", campaign))
        .isEqualTo(100);
    assertThat(db.list("SELECT * FROM room_daily_inventory WHERE room_type_id=?", f.room()))
        .allSatisfy(
            r -> {
              assertThat(Db.number(r, "held")).isEqualTo(100);
              assertThat(Db.number(r, "available")).isZero();
            });
    invariant(f);
    Files.writeString(
        Path.of("target/seckill-result.json"),
        "{\"users\":1000,\"quota\":100,\"successfulOrders\":100,\"nights\":3,\"elapsedMillisIncludingAsync\":"
            + ((System.nanoTime() - begin) / 1_000_000)
            + ",\"kind\":\"in-process service concurrency, not HTTP QPS\"}");
  }

  @Test
  void databaseInventoryFailureCompensatesAndRebuilds() {
    var f = fixture(3, 2);
    long campaign = campaign(f, 10);
    var requests = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 10; i++) requests.add(seckill.request(campaign, user()));
    process(campaign, 10);
    assertThat(
            db.count(
                "SELECT COUNT(*) FROM seckill_requests WHERE campaign_id=? AND status='SUCCESS'",
                campaign))
        .isEqualTo(3);
    assertThat(db.count("SELECT quota_used FROM seckill_campaigns WHERE id=?", campaign))
        .isEqualTo(3);
    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> "7".equals(redis.opsForValue().get(seckill.keys(campaign).getFirst())));
    var failed =
        db.one(
            "SELECT * FROM seckill_requests WHERE campaign_id=? AND status='FAILED' LIMIT 1",
            campaign);
    assertThat(seckill.request(campaign, Db.id(failed, "user_id")).get("id"))
        .isEqualTo(failed.get("id"));
    redis.delete(seckill.keys(campaign));
    seckill.rebuild(campaign);
    assertThat(redis.opsForValue().get(seckill.keys(campaign).getFirst())).isEqualTo("7");
    var success =
        db.one(
            "SELECT * FROM seckill_requests WHERE campaign_id=? AND status='SUCCESS' LIMIT 1",
            campaign);
    long booking = Db.id(success, "booking_id");
    bookings.cancel(booking);
    assertThat(redis.opsForValue().get(seckill.keys(campaign).getFirst())).isEqualTo("7");
    assertThat(db.count("SELECT quota_used FROM seckill_campaigns WHERE id=?", campaign))
        .isEqualTo(3);
    invariant(f);
  }

  @Test
  void sameUserAndDuplicateMessageStable() throws Exception {
    var f = fixture(10, 1);
    long campaign = campaign(f, 10);
    seckill.rebuild(campaign);
    var ids = concurrent(4, i -> (String) seckill.request(campaign, f.user()).get("id"));
    assertThat(new HashSet<>(ids)).hasSize(1);
    process(campaign, 1);
    String id = ids.getFirst();
    processor.process(UUID.randomUUID().toString(), "SECKILL_REQUESTED", id);
    assertThat(db.count("SELECT COUNT(*) FROM bookings WHERE campaign_id=?", campaign))
        .isEqualTo(1);
    assertThat(seckill.request(campaign, f.user()).get("id")).isEqualTo(id);
  }

  @Test
  void persistFailureAndAbandonedLuaLeaseRestoreAdmission() {
    var f = fixture(1, 1);
    long campaign = campaign(f, 1);
    seckill.rebuild(campaign);
    assertThatThrownBy(() -> seckill.request(campaign, 999999999L))
        .hasMessage("ADMISSION_UNAVAILABLE");
    assertThat(redis.opsForValue().get(seckill.keys(campaign).getFirst())).isEqualTo("1");
    var lua = new DefaultRedisScript<String>();
    lua.setLocation(new ClassPathResource("lua/admit.lua"));
    lua.setResultType(String.class);
    String token = UUID.randomUUID().toString();
    assertThat(
            redis.execute(
                lua,
                seckill.keys(campaign),
                Long.toString(f.user()),
                token,
                Long.toString(Instant.now().minusSeconds(60).toEpochMilli())))
        .isEqualTo(token);
    seckill.recoverPending();
    assertThat(redis.opsForValue().get(seckill.keys(campaign).getFirst())).isEqualTo("1");
    seckill.release(campaign, f.user(), token);
    assertThat(redis.opsForValue().get(seckill.keys(campaign).getFirst())).isEqualTo("1");
  }

  @Test
  void activityTimeAndRedisFailureAreRejected() {
    var f = fixture(1, 1);
    long campaign = campaign(f, 1);
    db.exec(
        "UPDATE seckill_campaigns SET starts_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 10 MINUTE) WHERE id=?",
        campaign);
    assertThatThrownBy(() -> seckill.request(campaign, f.user())).hasMessage("CAMPAIGN_NOT_ACTIVE");
    db.exec(
        "UPDATE seckill_campaigns SET starts_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),ends_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE) WHERE id=?",
        campaign);
    assertThatThrownBy(() -> seckill.request(campaign, f.user())).hasMessage("CAMPAIGN_NOT_ACTIVE");
    long active = campaign(f, 1);
    var docker = Containers.REDIS.getDockerClient();
    docker.pauseContainerCmd(Containers.REDIS.getContainerId()).exec();
    try {
      assertThatThrownBy(() -> seckill.request(active, f.user()))
          .hasMessage("ADMISSION_UNAVAILABLE");
      assertThat(db.count("SELECT COUNT(*) FROM seckill_requests WHERE campaign_id=?", active))
          .isZero();
    } finally {
      docker.unpauseContainerCmd(Containers.REDIS.getContainerId()).exec();
    }
  }

  @Test
  void consumerStoppedThenRestartedDoesNotLoseQueuedOrder() {
    var f = fixture(1, 1);
    long campaign = campaign(f, 1);
    var worker = listeners.getListenerContainer("hrms-worker");
    worker.stop();
    try {
      seckill.request(campaign, f.user());
      publisher.drain(5000);
      assertThat(
              db.one("SELECT status FROM seckill_requests WHERE campaign_id=?", campaign)
                  .get("status"))
          .isEqualTo("QUEUED");
    } finally {
      worker.start();
    }
    await()
        .atMost(Duration.ofSeconds(15))
        .until(
            () ->
                "SUCCESS"
                    .equals(
                        db.one("SELECT status FROM seckill_requests WHERE campaign_id=?", campaign)
                            .get("status")));
    invariant(f);
  }
}
