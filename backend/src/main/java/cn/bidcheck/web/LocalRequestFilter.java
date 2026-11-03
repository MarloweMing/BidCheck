package cn.bidcheck.web;

import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Local workbench: reject cross-site mutations without exposing an unauthenticated public API. */
@Component
public class LocalRequestFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        response.setHeader("X-Content-Type-Options","nosniff");
        response.setHeader("Referrer-Policy","same-origin");
        response.setHeader("X-Frame-Options","DENY");
        response.setHeader("Cache-Control","no-store");
        response.setHeader("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'");
        String origin=request.getHeader("Origin");
        // An attacker-controlled hostname can rebind to loopback and otherwise become "same-origin".
        if(!cn.bidcheck.compat.Java8Api.setOf("localhost", "127.0.0.1", "::1", "[::1]").contains(request.getServerName().toLowerCase(java.util.Locale.ROOT))
            ||!cn.bidcheck.compat.Java8Api.setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(request.getRemoteAddr())) {
            reject(response,403,"本机工作台仅接受本机地址访问"); return;
        }
        if("cross-site".equals(request.getHeader("Sec-Fetch-Site"))) { reject(response,403,"不允许跨站访问本机数据"); return; }
        if(origin!=null) {
            boolean valid=false;
            try { URI uri=URI.create(origin); int port=uri.getPort()<0?("https".equals(uri.getScheme())?443:80):uri.getPort();
                valid=uri.getHost()!=null&&uri.getHost().equals(request.getServerName())&&port==request.getServerPort()&&uri.getScheme().equals(request.getScheme());
            } catch(IllegalArgumentException ignored) {}
            if(!valid) { reject(response,403,"不允许跨站访问本机数据"); return; }
        }
        if(request.getContentType()!=null&&request.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            if(request.getContentLengthLong()>65536) { reject(response,413,"请求内容不能超过64KB"); return; }
            request=new BoundedJsonRequest(request);
        }
        chain.doFilter(request,response);
    }
    private void reject(HttpServletResponse response,int status,String message) throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\""+message+"\"}");
    }
    private static final class BoundedJsonRequest extends HttpServletRequestWrapper {
        private ServletInputStream stream;
        BoundedJsonRequest(HttpServletRequest request) { super(request); }
        @Override public ServletInputStream getInputStream() throws IOException {
            if(stream!=null) return stream;
            ServletInputStream delegate=super.getInputStream();
            stream=new ServletInputStream() {
                private int consumed;
                @Override public int read() throws IOException { int value=delegate.read(); if(value!=-1&&++consumed>65536) throw new IOException("JSON request exceeds limit"); return value; }
                @Override public int read(byte[] bytes,int offset,int length) throws IOException {
                    int count=delegate.read(bytes,offset,Math.min(length,65537-consumed));
                    if(count>0&&(consumed+=count)>65536) throw new IOException("JSON request exceeds limit"); return count;
                }
                @Override public boolean isFinished() { return delegate.isFinished(); }
                @Override public boolean isReady() { return delegate.isReady(); }
                @Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }
            };
            return stream;
        }
    }
}
