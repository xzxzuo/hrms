package com.hrms.inventory;
import com.hrms.shared.*;import org.springframework.stereotype.Service;import java.time.LocalDate;import java.math.BigDecimal;import java.util.*;
@Service public class InventoryService {
 private final Db db;public InventoryService(Db db){this.db=db;}
 /** All methods participate in the caller's transaction. Dates must be supplied in ascending order. */
 public void hold(long booking,long room,LocalDate date,int qty,BigDecimal price){
  Problem.require(db.exec("UPDATE room_daily_inventory SET available=available-?,held=held+?,version=version+1 WHERE room_type_id=? AND stay_date=? AND available>=? AND sale_status='OPEN'",qty,qty,room,date,qty)==1,409,"INVENTORY_UNAVAILABLE");
  db.exec("INSERT INTO booking_nights(booking_id,room_type_id,stay_date,quantity,unit_price) VALUES(?,?,?,?,?)",booking,room,date,qty,price);ledger(booking,room,date,"HOLD",-qty,qty,0);
 }
 public void transition(long booking,String operation){
 for(var night:db.list("SELECT * FROM booking_nights WHERE booking_id=? ORDER BY stay_date",booking)){
  int qty=Db.number(night,"quantity");long room=Db.id(night,"room_type_id");Object date=night.get("stay_date");int da=0,dh=0,ds=0;String sql;
  switch(operation){case "CONFIRM" -> {dh=-qty;ds=qty;sql="UPDATE room_daily_inventory SET held=held-?,sold=sold+?,version=version+1 WHERE room_type_id=? AND stay_date=? AND held>=?";}
   case "RELEASE_HELD" -> {dh=-qty;da=qty;sql="UPDATE room_daily_inventory SET held=held-?,available=available+?,version=version+1 WHERE room_type_id=? AND stay_date=? AND held>=?";}
   case "RELEASE_SOLD" -> {ds=-qty;da=qty;sql="UPDATE room_daily_inventory SET sold=sold-?,available=available+?,version=version+1 WHERE room_type_id=? AND stay_date=? AND sold>=?";}
   default -> throw new IllegalArgumentException("Invalid inventory operation");}
  Problem.require(db.exec(sql,qty,qty,room,date,qty)==1,409,"INVENTORY_INCONSISTENT");ledger(booking,room,date,operation,da,dh,ds);
 }
 }
 private void ledger(long booking,long room,Object date,String op,int da,int dh,int ds){db.exec("INSERT INTO inventory_ledger(booking_id,room_type_id,stay_date,operation,delta_available,delta_held,delta_sold) VALUES(?,?,?,?,?,?,?)",booking,room,date,op,da,dh,ds);}
}
