package com.hrms.shared;
public class Problem extends RuntimeException {
 public final int status; public final String code;
 public Problem(int status,String code) { super(code); this.status=status;this.code=code; }
 public static void require(boolean ok,int status,String code) { if(!ok) throw new Problem(status,code); }
 public static <T> T found(T row) { require(row!=null,404,"NOT_FOUND"); return row; }
}
