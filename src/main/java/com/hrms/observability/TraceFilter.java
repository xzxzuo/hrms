package com.hrms.observability;
import java.io.IOException;import java.util.UUID;
import jakarta.servlet.*;import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;import org.springframework.web.filter.OncePerRequestFilter;import org.slf4j.MDC;
@Component public class TraceFilter extends OncePerRequestFilter {
 protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
 String id=UUID.randomUUID().toString(); req.setAttribute("traceId",id);res.setHeader("X-Request-Id",id);MDC.put("traceId",id);
 try {chain.doFilter(req,res);} finally {MDC.remove("traceId");}
 }
}
