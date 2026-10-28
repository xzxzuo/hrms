package com.hrms;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrms.shared.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class DatabaseFailureIT extends TestSupport {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  @Test
  void mysqlUnavailableReturns503WithoutPromisingAnOrder() throws Exception {
    var f = fixture(1, 1);
    String quote = quote(f, f.user());
    var docker = Containers.MYSQL.getDockerClient();
    docker.pauseContainerCmd(Containers.MYSQL.getContainerId()).exec();
    try {
      mvc.perform(
              post("/api/bookings")
                  .with(jwt().jwt(j -> j.subject(Long.toString(f.user()))))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      json.writeValueAsString(
                          Map.of("quoteId", quote, "clientRequestId", "db-down"))))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("code").value("DATABASE_UNAVAILABLE"));
    } finally {
      docker.unpauseContainerCmd(Containers.MYSQL.getContainerId()).exec();
    }
    assertThat(db.count("SELECT COUNT(*) FROM bookings WHERE room_type_id=?", f.room())).isZero();
    invariant(f);
  }
}
