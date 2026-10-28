package com.hrms;
import com.hrms.shared.*;import com.hrms.pricing.PricingService;import java.util.*;import java.time.*;import org.junit.jupiter.api.*;import static org.assertj.core.api.Assertions.*;import org.springframework.beans.factory.annotation.Autowired;import org.springframework.boot.test.context.SpringBootTest;import org.springframework.test.context.DynamicPropertySource;import org.springframework.test.context.DynamicPropertyRegistry;import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;import org.springframework.test.web.servlet.MockMvc;import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;import org.springframework.http.MediaType;import com.fasterxml.jackson.databind.ObjectMapper;import org.springframework.security.crypto.password.PasswordEncoder;import java.nio.file.*;
class FoundationIT extends TestSupport {
 @Autowired Db db;@Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired PricingService pricing;@Autowired PasswordEncoder passwords;
 @Test void registrationLoginAndOwnership() throws Exception {
 String email=UUID.randomUUID()+"@test.local";String body=json.writeValueAsString(Map.of("email",email,"password","test-password-123"));
 mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
 var login=mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn();String token=json.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
 mvc.perform(get("/api/me").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("email").value(email));
 mvc.perform(post("/api/admin/hotels").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
 long uid=Db.id(db.one("SELECT id FROM users WHERE email=?",email),"id");db.exec("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE name='HOTEL_MANAGER'",uid);
 token=json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getContentAsString()).get("accessToken").asText();
 mvc.perform(put("/api/admin/hotels/999/status").header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("status","OPEN")))).andExpect(status().isForbidden());
 }
 @Test void migrationCalendarAndQuotes() throws Exception {
 long uid=db.insert("INSERT INTO users(email,password_hash) VALUES(?,?)",UUID.randomUUID()+"@test.local",passwords.encode("test-password-123"));long hotel=db.insert("INSERT INTO hotels(name,city,address,timezone,status,facilities,images) VALUES('Test','Stockholm','x','UTC','OPEN','[]','[]')");long room=db.insert("INSERT INTO room_types(hotel_id,name,bed_type,capacity,base_price,room_count) VALUES(?,'Queen','Queen',2,500,3)",hotel);
 LocalDate in=LocalDate.now(ZoneOffset.UTC).plusDays(5);for(int i=0;i<3;i++){db.exec("INSERT INTO room_daily_inventory(room_type_id,stay_date,total,available) VALUES(?,?,3,3)",room,in.plusDays(i));db.exec("INSERT INTO room_daily_rate(room_type_id,stay_date,price) VALUES(?,?,500)",room,in.plusDays(i));}
 var quote=pricing.quote(uid,room,in,in.plusDays(3),2);assertThat(quote.get("total").toString()).isEqualTo("3000.00");assertThat((List<?>)quote.get("nights")).hasSize(3);
 mvc.perform(get("/api/room-types/"+room+"/calendar").param("checkIn",in.toString()).param("checkOut",in.plusDays(3).toString())).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
 assertThatThrownBy(()->pricing.quote(uid,room,in,in,1)).isInstanceOf(Problem.class);
 assertThatThrownBy(()->db.exec("UPDATE room_daily_inventory SET available=-1 WHERE room_type_id=?",room)).isInstanceOf(org.springframework.dao.DataAccessException.class);
 var plan=db.list("EXPLAIN SELECT * FROM room_daily_inventory WHERE room_type_id=? AND stay_date>=? AND stay_date<? ORDER BY stay_date",room,in,in.plusDays(3));Files.createDirectories(Path.of("target"));Files.writeString(Path.of("target/explain.txt"),plan.toString());
 }
}
