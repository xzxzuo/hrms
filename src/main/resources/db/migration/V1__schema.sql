CREATE TABLE users (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, email VARCHAR(190) NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL, created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;
CREATE TABLE roles (id BIGINT PRIMARY KEY AUTO_INCREMENT,name VARCHAR(30) NOT NULL UNIQUE);
INSERT INTO roles(name) VALUES ('USER'),('HOTEL_MANAGER'),('ADMIN');
CREATE TABLE user_roles (user_id BIGINT NOT NULL,role_id BIGINT NOT NULL,PRIMARY KEY(user_id,role_id),FOREIGN KEY(user_id) REFERENCES users(id),FOREIGN KEY(role_id) REFERENCES roles(id));
CREATE TABLE hotels (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,name VARCHAR(120) NOT NULL,city VARCHAR(80) NOT NULL,address VARCHAR(255) NOT NULL,
 timezone VARCHAR(60) NOT NULL DEFAULT 'UTC',status VARCHAR(20) NOT NULL DEFAULT 'DRAFT', facilities JSON NOT NULL,images JSON NOT NULL,
 CHECK(status IN ('DRAFT','OPEN','CLOSED')),INDEX ix_hotels_city_status(city,status,id)
);
CREATE TABLE hotel_managers (hotel_id BIGINT NOT NULL,user_id BIGINT NOT NULL,PRIMARY KEY(hotel_id,user_id),INDEX(user_id,hotel_id),FOREIGN KEY(hotel_id) REFERENCES hotels(id),FOREIGN KEY(user_id) REFERENCES users(id));
CREATE TABLE room_types (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,hotel_id BIGINT NOT NULL,name VARCHAR(100) NOT NULL,bed_type VARCHAR(50) NOT NULL,
 capacity INT NOT NULL,base_price DECIMAL(12,2) NOT NULL,room_count INT NOT NULL,status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',version INT NOT NULL DEFAULT 0,
 CHECK(capacity>0 AND base_price>0 AND room_count>0),CHECK(status IN ('ACTIVE','INACTIVE')),
 FOREIGN KEY(hotel_id) REFERENCES hotels(id),INDEX ix_room_hotel_status(hotel_id,status,capacity,base_price)
);
CREATE TABLE physical_rooms (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,room_type_id BIGINT NOT NULL,room_no VARCHAR(20) NOT NULL,status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
 UNIQUE(room_type_id,room_no),FOREIGN KEY(room_type_id) REFERENCES room_types(id),CHECK(status IN ('AVAILABLE','OCCUPIED','CLEANING','OUT_OF_SERVICE'))
);
CREATE TABLE room_daily_inventory (
 room_type_id BIGINT NOT NULL,stay_date DATE NOT NULL,total INT NOT NULL,available INT NOT NULL,held INT NOT NULL DEFAULT 0,sold INT NOT NULL DEFAULT 0,
 blocked INT NOT NULL DEFAULT 0,sale_status VARCHAR(10) NOT NULL DEFAULT 'OPEN',version INT NOT NULL DEFAULT 0,
 PRIMARY KEY(room_type_id,stay_date),FOREIGN KEY(room_type_id) REFERENCES room_types(id),
 CHECK(total=available+held+sold+blocked),CHECK(total>=0 AND available>=0 AND held>=0 AND sold>=0 AND blocked>=0),CHECK(sale_status IN ('OPEN','CLOSED'))
);
CREATE TABLE room_daily_rate (
 room_type_id BIGINT NOT NULL,stay_date DATE NOT NULL,price DECIMAL(12,2) NOT NULL,version INT NOT NULL DEFAULT 0,
 PRIMARY KEY(room_type_id,stay_date),FOREIGN KEY(room_type_id) REFERENCES room_types(id),CHECK(price>0)
);
CREATE TABLE quotes (
 id CHAR(36) PRIMARY KEY,user_id BIGINT NOT NULL,room_type_id BIGINT NOT NULL,check_in DATE NOT NULL,check_out DATE NOT NULL,quantity INT NOT NULL,
 total DECIMAL(12,2) NOT NULL,expires_at DATETIME(6) NOT NULL,FOREIGN KEY(user_id) REFERENCES users(id),FOREIGN KEY(room_type_id) REFERENCES room_types(id),
 CHECK(check_out>check_in AND quantity>0 AND total>=0),INDEX(expires_at)
);
CREATE TABLE quote_nights (quote_id CHAR(36) NOT NULL,stay_date DATE NOT NULL,price DECIMAL(12,2) NOT NULL,PRIMARY KEY(quote_id,stay_date),FOREIGN KEY(quote_id) REFERENCES quotes(id));
CREATE TABLE coupons (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,hotel_id BIGINT NOT NULL,name VARCHAR(100) NOT NULL,discount DECIMAL(12,2) NOT NULL,min_total DECIMAL(12,2) NOT NULL DEFAULT 0,
 expires_at DATETIME(6) NOT NULL,quota INT NOT NULL,claimed INT NOT NULL DEFAULT 0,FOREIGN KEY(hotel_id) REFERENCES hotels(id),CHECK(discount>0 AND min_total>=0 AND claimed>=0 AND claimed<=quota)
);
CREATE TABLE user_coupons (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,user_id BIGINT NOT NULL,coupon_id BIGINT NOT NULL,status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',booking_id BIGINT NULL,
 UNIQUE(user_id,coupon_id),FOREIGN KEY(user_id) REFERENCES users(id),FOREIGN KEY(coupon_id) REFERENCES coupons(id),CHECK(status IN ('AVAILABLE','RESERVED','USED','EXPIRED'))
);
CREATE TABLE seckill_campaigns (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,hotel_id BIGINT NOT NULL,room_type_id BIGINT NOT NULL,check_in DATE NOT NULL,check_out DATE NOT NULL,
 starts_at DATETIME(6) NOT NULL,ends_at DATETIME(6) NOT NULL,price DECIMAL(12,2) NOT NULL,quota INT NOT NULL,quota_used INT NOT NULL DEFAULT 0,status VARCHAR(10) NOT NULL DEFAULT 'OPEN',
 FOREIGN KEY(hotel_id) REFERENCES hotels(id),FOREIGN KEY(room_type_id) REFERENCES room_types(id),CHECK(check_out>check_in AND ends_at>starts_at AND price>0 AND quota>0 AND quota_used>=0 AND quota_used<=quota),CHECK(status IN ('OPEN','CLOSED'))
);
CREATE TABLE bookings (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,user_id BIGINT NOT NULL,client_request_id VARCHAR(100) NOT NULL,request_fingerprint VARCHAR(190) NOT NULL,
 quote_id CHAR(36),hotel_id BIGINT NOT NULL,room_type_id BIGINT NOT NULL,check_in DATE NOT NULL,check_out DATE NOT NULL,quantity INT NOT NULL,
 subtotal DECIMAL(12,2) NOT NULL,discount DECIMAL(12,2) NOT NULL DEFAULT 0,total DECIMAL(12,2) NOT NULL,currency CHAR(3) NOT NULL DEFAULT 'CNY',
 status VARCHAR(30) NOT NULL,expires_at DATETIME(6) NOT NULL,coupon_id BIGINT NULL,campaign_id BIGINT NULL,physical_room_id BIGINT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 UNIQUE(user_id,client_request_id),FOREIGN KEY(user_id) REFERENCES users(id),FOREIGN KEY(hotel_id) REFERENCES hotels(id),FOREIGN KEY(room_type_id) REFERENCES room_types(id),FOREIGN KEY(quote_id) REFERENCES quotes(id),FOREIGN KEY(coupon_id) REFERENCES user_coupons(id),FOREIGN KEY(campaign_id) REFERENCES seckill_campaigns(id),FOREIGN KEY(physical_room_id) REFERENCES physical_rooms(id),
 CHECK(status IN ('PENDING_PAYMENT','CONFIRMED','CANCELLED','EXPIRED','CHECKED_IN','CHECKED_OUT')),
 CHECK(check_out>check_in AND quantity>0 AND total>=0 AND subtotal>=discount AND discount>=0 AND total=subtotal-discount),
 INDEX ix_booking_expiry(status,expires_at,id),INDEX ix_booking_user(user_id,created_at,id),INDEX ix_booking_hotel(hotel_id,check_in,status)
);
ALTER TABLE user_coupons ADD CONSTRAINT fk_coupon_booking FOREIGN KEY(booking_id) REFERENCES bookings(id);
CREATE TABLE booking_nights (
 booking_id BIGINT NOT NULL,room_type_id BIGINT NOT NULL,stay_date DATE NOT NULL,quantity INT NOT NULL,unit_price DECIMAL(12,2) NOT NULL,
 PRIMARY KEY(booking_id,stay_date),FOREIGN KEY(booking_id) REFERENCES bookings(id),FOREIGN KEY(room_type_id,stay_date) REFERENCES room_daily_inventory(room_type_id,stay_date),INDEX(room_type_id,stay_date,booking_id)
);
CREATE TABLE inventory_ledger (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,booking_id BIGINT NULL,room_type_id BIGINT NOT NULL,stay_date DATE NOT NULL,operation VARCHAR(30) NOT NULL,
 delta_available INT NOT NULL,delta_held INT NOT NULL,delta_sold INT NOT NULL,created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(booking_id,stay_date,operation),FOREIGN KEY(booking_id) REFERENCES bookings(id),FOREIGN KEY(room_type_id,stay_date) REFERENCES room_daily_inventory(room_type_id,stay_date),INDEX(room_type_id,stay_date,id)
);
CREATE TABLE payments (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,booking_id BIGINT NOT NULL,provider_ref VARCHAR(100) NOT NULL UNIQUE,amount DECIMAL(12,2) NOT NULL,status VARCHAR(20) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),FOREIGN KEY(booking_id) REFERENCES bookings(id),CHECK(status IN ('SUCCEEDED','FAILED')),INDEX(booking_id,status)
);
CREATE TABLE refunds (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,payment_id BIGINT NOT NULL UNIQUE,booking_id BIGINT NOT NULL,amount DECIMAL(12,2) NOT NULL,status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),FOREIGN KEY(payment_id) REFERENCES payments(id),FOREIGN KEY(booking_id) REFERENCES bookings(id),CHECK(status IN ('REQUESTED','COMPLETED'))
);
CREATE TABLE seckill_requests (
 id CHAR(36) PRIMARY KEY,campaign_id BIGINT NOT NULL,user_id BIGINT NOT NULL,status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',booking_id BIGINT NULL,error_code VARCHAR(60) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),UNIQUE(campaign_id,user_id),FOREIGN KEY(campaign_id) REFERENCES seckill_campaigns(id),FOREIGN KEY(user_id) REFERENCES users(id),FOREIGN KEY(booking_id) REFERENCES bookings(id),CHECK(status IN ('QUEUED','SUCCESS','FAILED')),INDEX(status,created_at)
);
CREATE TABLE outbox_events (
 id CHAR(36) PRIMARY KEY,event_type VARCHAR(40) NOT NULL,aggregate_id VARCHAR(100) NOT NULL,status VARCHAR(12) NOT NULL DEFAULT 'NEW',attempts INT NOT NULL DEFAULT 0,
 next_attempt DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),sent_at DATETIME(6) NULL,
 INDEX ix_outbox_pending(status,next_attempt,id),CHECK(status IN ('NEW','SENT','DEAD'))
);
CREATE TABLE processed_messages (message_id CHAR(36) PRIMARY KEY,processed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6));
CREATE TABLE notifications (id BIGINT PRIMARY KEY AUTO_INCREMENT,event_id CHAR(36) NOT NULL UNIQUE,booking_id BIGINT NOT NULL,kind VARCHAR(40) NOT NULL,created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),FOREIGN KEY(booking_id) REFERENCES bookings(id));
CREATE TABLE audit_logs (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,actor_id BIGINT NULL,action VARCHAR(60) NOT NULL,entity_ref VARCHAR(190) NOT NULL,created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),FOREIGN KEY(actor_id) REFERENCES users(id),INDEX(actor_id,created_at),INDEX(action,created_at)
);
