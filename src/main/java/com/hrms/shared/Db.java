package com.hrms.shared;
import java.util.*;
import java.math.BigDecimal;
import org.springframework.stereotype.Repository;
@Repository
public class Db {
 private final SqlMapper mapper;
 public Db(SqlMapper mapper) { this.mapper=mapper; }
 private Map<String,Object> params(Object... values) { var p=new HashMap<String,Object>(); for(int i=0;i<values.length;i++) p.put("v"+i,values[i]); return p; }
 private String sql(String sql) { var out=new StringBuilder(); int n=0; for(char c:sql.toCharArray()) { if(c=='?') out.append("#{p.v").append(n++).append('}'); else out.append(c); } return out.toString(); }
 public List<Map<String,Object>> list(String sql,Object... args) { return mapper.rows(sql(sql),params(args)); }
 public Map<String,Object> one(String sql,Object... args) { var rows=list(sql,args); return rows.isEmpty()?null:rows.getFirst(); }
 public int exec(String sql,Object... args) { return mapper.exec(sql(sql),params(args)); }
 public long insert(String sql,Object... args) { var p=params(args); mapper.insert(sql(sql),p); return ((Number)p.get("id")).longValue(); }
 public long count(String sql,Object... args) { return ((Number)one(sql,args).values().iterator().next()).longValue(); }
 public static long id(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
 public static int number(Map<String,Object> row,String key) { return ((Number)row.get(key)).intValue(); }
 public static BigDecimal money(Map<String,Object> row,String key) { return new BigDecimal(row.get(key).toString()); }
}
