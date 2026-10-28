package com.hrms.identity;
import java.nio.charset.StandardCharsets;import javax.crypto.spec.SecretKeySpec;import java.time.Instant;import java.util.List;
import org.springframework.context.annotation.*;import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;import org.springframework.security.oauth2.jose.jws.MacAlgorithm;import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.core.*;import com.nimbusds.jose.jwk.source.ImmutableSecret;
@Configuration public class SecurityConfig {
 @Bean SecretKeySpec jwtKey(@Value("${hrms.jwt-secret}") String secret) { if(secret.length()<32) throw new IllegalArgumentException("JWT_SECRET must have at least 32 characters"); return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"); }
 @Bean JwtEncoder encoder(SecretKeySpec key) { return new NimbusJwtEncoder(new ImmutableSecret<>(key)); }
 @Bean JwtDecoder decoder(SecretKeySpec key) {var d=NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();d.setJwtValidator(JwtValidators.createDefaultWithIssuer("hrms"));return d;}
 @Bean PasswordEncoder passwords() {return new BCryptPasswordEncoder();}
 @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
 var converter=new JwtAuthenticationConverter(); converter.setJwtGrantedAuthoritiesConverter(jwt -> jwt.getClaimAsStringList("roles").stream().<org.springframework.security.core.GrantedAuthority>map(r->new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_"+r)).toList());
 return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
 .authorizeHttpRequests(a->a.requestMatchers("/api/auth/**","/api/payment/callback","/v3/api-docs/**","/swagger-ui/**","/swagger-ui.html","/actuator/health/**","/actuator/prometheus").permitAll().requestMatchers(org.springframework.http.HttpMethod.GET,"/api/hotels/**","/api/room-types/**").permitAll().requestMatchers("/api/admin/**").hasAnyRole("HOTEL_MANAGER","ADMIN").anyRequest().authenticated())
 .oauth2ResourceServer(o->o.jwt(j->j.jwtAuthenticationConverter(converter))).build();
 }
}
