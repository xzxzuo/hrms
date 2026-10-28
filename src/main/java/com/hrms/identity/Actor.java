package com.hrms.identity;

import com.hrms.shared.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class Actor {
  private final Db db;

  public Actor(Db db) {
    this.db = db;
  }

  public long id() {
    return Long.parseLong(
        ((Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getSubject());
  }

  public boolean role(String role) {
    return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
        .anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
  }

  public void admin() {
    Problem.require(role("ADMIN"), 403, "FORBIDDEN");
  }

  public void hotel(long hotelId) {
    Problem.require(
        role("ADMIN")
            || (role("HOTEL_MANAGER")
                && db.count(
                        "SELECT COUNT(*) FROM hotel_managers WHERE user_id=? AND hotel_id=?",
                        id(),
                        hotelId)
                    > 0),
        403,
        "HOTEL_FORBIDDEN");
  }

  public void owner(long userId) {
    Problem.require(id() == userId, 403, "OWNER_FORBIDDEN");
  }
}
