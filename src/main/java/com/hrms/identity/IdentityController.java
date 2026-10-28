package com.hrms.identity;

import com.hrms.shared.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class IdentityController {
  private final Db db;
  private final Tx tx;
  private final PasswordEncoder encoder;
  private final JwtEncoder jwt;
  private final Actor actor;
  private final com.hrms.observability.Audit audit;

  public IdentityController(
      Db db,
      Tx tx,
      PasswordEncoder encoder,
      JwtEncoder jwt,
      Actor actor,
      com.hrms.observability.Audit audit) {
    this.db = db;
    this.tx = tx;
    this.encoder = encoder;
    this.jwt = jwt;
    this.actor = actor;
    this.audit = audit;
  }

  public record Credentials(
      @NotBlank @Email @Size(max = 190) String email,
      @NotBlank @Size(min = 10, max = 72) String password) {}

  @PostMapping("/auth/register")
  public Map<String, Object> register(@Valid @RequestBody Credentials c) {
    long id =
        tx.run(
            () -> {
              long uid =
                  db.insert(
                      "INSERT INTO users(email,password_hash) VALUES(?,?)",
                      c.email().toLowerCase(Locale.ROOT),
                      encoder.encode(c.password()));
              db.exec(
                  "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE name='USER'",
                  uid);
              return uid;
            });
    return Map.of("id", id);
  }

  @PostMapping("/auth/login")
  public Map<String, Object> login(@Valid @RequestBody Credentials c) {
    var u =
        db.one(
            "SELECT id,password_hash FROM users WHERE email=?", c.email().toLowerCase(Locale.ROOT));
    Problem.require(
        u != null && encoder.matches(c.password(), (String) u.get("password_hash")),
        401,
        "INVALID_CREDENTIALS");
    long id = Db.id(u, "id");
    var roles =
        db
            .list(
                "SELECT r.name FROM roles r JOIN user_roles ur ON ur.role_id=r.id WHERE ur.user_id=?",
                id)
            .stream()
            .map(r -> (String) r.get("name"))
            .toList();
    var claims =
        JwtClaimsSet.builder()
            .issuer("hrms")
            .subject(Long.toString(id))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .claim("roles", roles)
            .build();
    return Map.of(
        "accessToken",
        jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue(),
        "expiresIn",
        3600);
  }

  @GetMapping("/me")
  public Map<String, Object> me() {
    return Problem.found(db.one("SELECT id,email,created_at FROM users WHERE id=?", actor.id()));
  }

  @PutMapping("/admin/users/{id}/roles/{role}")
  public Map<String, Object> role(@PathVariable long id, @PathVariable String role) {
    actor.admin();
    Problem.require(Set.of("USER", "HOTEL_MANAGER", "ADMIN").contains(role), 400, "INVALID_ROLE");
    return tx.run(
        () -> {
          db.exec(
              "INSERT IGNORE INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE name=?",
              id,
              role);
          audit.log(actor.id(), "ROLE_GRANT", id + ":" + role);
          return Map.of("userId", id, "role", role);
        });
  }
}
