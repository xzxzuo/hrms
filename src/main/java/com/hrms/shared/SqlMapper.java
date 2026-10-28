package com.hrms.shared;
import java.util.*;
import org.apache.ibatis.annotations.*;
/** SQL is supplied only by repository code; all request values are bound parameters. */
@Mapper
public interface SqlMapper {
 class Provider { public static String sql(Map<String,Object> args) { return (String)args.get("sql"); } }
 @SelectProvider(type=Provider.class,method="sql") List<Map<String,Object>> rows(@Param("sql") String sql,@Param("p") Map<String,Object> params);
 @UpdateProvider(type=Provider.class,method="sql") int exec(@Param("sql") String sql,@Param("p") Map<String,Object> params);
 @InsertProvider(type=Provider.class,method="sql") @Options(useGeneratedKeys=true,keyProperty="p.id") int insert(@Param("sql") String sql,@Param("p") Map<String,Object> params);
}
