package com.hrms;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrms.observability.Reconciliation;
import com.hrms.shared.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

class AdminIT extends TestSupport {
  @Autowired Reconciliation reconciliation;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired PasswordEncoder passwords;

  String login(long user, String role) throws Exception {
    String email = "admin-" + user + "@test.local";
    db.exec(
        "UPDATE users SET email=?,password_hash=? WHERE id=?",
        email,
        passwords.encode("test-password-123"),
        user);
    db.exec(
        "INSERT IGNORE INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE name=?",
        user,
        role);
    return json.readTree(
            mvc.perform(
                    post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            json.writeValueAsString(
                                Map.of("email", email, "password", "test-password-123"))))
                .andReturn()
                .getResponse()
                .getContentAsString())
        .get("accessToken")
        .asText();
  }

  @Test
  void scopedReportsAndReconciliation() throws Exception {
    var f = fixture(2, 1);
    long id = book(f);
    payments.simulate(id, "report-" + id, true);
    String token = login(f.user(), "HOTEL_MANAGER");
    db.exec("INSERT INTO hotel_managers(hotel_id,user_id) VALUES(?,?)", f.hotel(), f.user());
    mvc.perform(
            get("/api/admin/hotels/" + f.hotel() + "/report")
                .param("date", f.in().toString())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("reservationOccupancyPct").value(50));
    mvc.perform(
            get("/api/admin/hotels/999999/report")
                .param("date", f.in().toString())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
    var result = reconciliation.check();
    assertThat((List<?>) result.get("inventoryMismatches")).isEmpty();
    assertThat((List<?>) result.get("campaignMismatches")).isEmpty();
    db.exec(
        "UPDATE room_daily_inventory SET available=available-1,held=held+1 WHERE room_type_id=?",
        f.room());
    assertThat((List<?>) reconciliation.check().get("inventoryMismatches")).isNotEmpty();
    db.exec(
        "UPDATE room_daily_inventory SET available=available+1,held=held-1 WHERE room_type_id=?",
        f.room());
  }

  @Test
  void securityRejectsTamperedJwtAndExposesActualOpenApi() throws Exception {
    mvc.perform(get("/api/bookings"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("code").value("UNAUTHORIZED"));
    mvc.perform(get("/api/bookings").header("Authorization", "Bearer malformed"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paths['/api/bookings']").exists())
        .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"));
    mvc.perform(post("/api/payment/callback").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
  }
}
