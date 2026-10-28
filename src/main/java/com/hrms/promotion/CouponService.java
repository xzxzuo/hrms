package com.hrms.promotion;
import com.hrms.shared.*;import org.springframework.stereotype.Service;import java.math.BigDecimal;import java.util.*;
@Service public class CouponService {
 private final Db db;private final Tx tx;public CouponService(Db db,Tx tx){this.db=db;this.tx=tx;}
 public Map<String,Object> claim(long user,long coupon){return tx.run(()->{Problem.found(db.one("SELECT id FROM coupons WHERE id=? FOR UPDATE",coupon));var old=db.one("SELECT * FROM user_coupons WHERE user_id=? AND coupon_id=?",user,coupon);if(old!=null)return old;Problem.require(db.exec("UPDATE coupons SET claimed=claimed+1 WHERE id=? AND claimed<quota AND expires_at>UTC_TIMESTAMP(6)",coupon)==1,409,"COUPON_UNAVAILABLE");long id=db.insert("INSERT INTO user_coupons(user_id,coupon_id) VALUES(?,?)",user,coupon);return db.one("SELECT * FROM user_coupons WHERE id=?",id);});}
 public BigDecimal reserve(Long coupon,long user,long hotel,BigDecimal subtotal){if(coupon==null)return BigDecimal.ZERO;
 var c=Problem.found(db.one("SELECT uc.*,c.discount,c.min_total,c.hotel_id,c.expires_at>UTC_TIMESTAMP(6) AS valid FROM user_coupons uc JOIN coupons c ON c.id=uc.coupon_id WHERE uc.id=? FOR UPDATE",coupon));
 Problem.require(Db.id(c,"user_id")==user&&Db.id(c,"hotel_id")==hotel,403,"COUPON_FORBIDDEN");Problem.require(c.get("status").equals("AVAILABLE")&&Db.number(c,"valid")==1&&subtotal.compareTo(Db.money(c,"min_total"))>=0,409,"COUPON_UNAVAILABLE");return Db.money(c,"discount").min(subtotal);}
 public void attach(Long coupon,long booking){if(coupon!=null)Problem.require(db.exec("UPDATE user_coupons SET status='RESERVED',booking_id=? WHERE id=? AND status='AVAILABLE'",booking,coupon)==1,409,"COUPON_UNAVAILABLE");}
 public void confirm(long booking){db.exec("UPDATE user_coupons SET status='USED' WHERE booking_id=? AND status='RESERVED'",booking);}
 public void release(long booking){db.exec("UPDATE user_coupons uc JOIN coupons c ON c.id=uc.coupon_id SET uc.status=IF(c.expires_at>UTC_TIMESTAMP(6),'AVAILABLE','EXPIRED'),uc.booking_id=NULL WHERE uc.booking_id=? AND uc.status='RESERVED'",booking);}
}
