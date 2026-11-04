package cn.bidcheck.verification.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaAgentHttpTest {
    @Test void slowResponseBodyKeepsTimeoutClassificationAndNeverRetries()throws Exception{
        HttpServer server=server();AtomicInteger calls=new AtomicInteger();CountDownLatch release=new CountDownLatch(1);
        server.createContext("/chat/completions",exchange->{try{calls.incrementAndGet();exchange.sendResponseHeaders(200,100);exchange.getResponseBody().write('{');exchange.getResponseBody().flush();release.await(3,TimeUnit.SECONDS);}catch(Exception ignored){}finally{exchange.close();}});server.start();
        try{JavaAgentRunner.Failure failure=assertThrows(JavaAgentRunner.Failure.class,()->new JavaAgentRunner.HttpTransport(base(server),"synthetic-key",json).complete(Collections.emptyMap(),Duration.ofMillis(150)));assertTrue(Arrays.asList("INVESTIGATION_TIMEOUT","PROVIDER_TIMEOUT").contains(failure.code),failure.code);assertEquals(1,calls.get());}
        finally{release.countDown();server.stop(0);}
    }
    @Test void completeMalformedJsonRemainsAProtocolErrorAndIsNeverRetried()throws Exception{
        HttpServer server=server();AtomicInteger calls=new AtomicInteger();server.createContext("/chat/completions",exchange->{try{calls.incrementAndGet();byte[] body="{invalid-json}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}finally{exchange.close();}});server.start();
        try{assertEquals("MODEL_PROTOCOL_ERROR",assertThrows(JavaAgentRunner.Failure.class,()->new JavaAgentRunner.HttpTransport(base(server),"synthetic-key",json).complete(Collections.emptyMap(),Duration.ofSeconds(5))).code);assertEquals(1,calls.get());}
        finally{server.stop(0);}
    }
    @Test void interruptedResponseBodyRetriesAndExhaustionIsAConnectionFailure()throws Exception{
        for(boolean recover:Arrays.asList(true,false)){
            HttpServer server=server();AtomicInteger calls=new AtomicInteger();
            server.createContext("/chat/completions",exchange->{try{
                int attempt=calls.incrementAndGet();byte[] body="{}".getBytes(StandardCharsets.UTF_8);
                if(recover&&attempt==2){exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}
                else{exchange.sendResponseHeaders(200,100);exchange.getResponseBody().write(body);}
            }finally{exchange.close();}});server.start();
            try{
                JavaAgentRunner.HttpTransport transport=new JavaAgentRunner.HttpTransport(base(server),"synthetic-key",json);
                if(recover){assertEquals(Collections.emptyMap(),transport.complete(Collections.emptyMap(),Duration.ofSeconds(5)));assertEquals(2,calls.get());}
                else{assertEquals("PROVIDER_CONNECTION",assertThrows(JavaAgentRunner.Failure.class,()->transport.complete(Collections.emptyMap(),Duration.ofSeconds(5))).code);assertEquals(3,calls.get());}
            }finally{server.stop(0);}
        }
    }
    @Test void everyClientErrorIsSingleAttemptIncludingRateLimits()throws Exception{
        for(int status:new int[]{400,401,403,404,429}){
            HttpServer server=server();AtomicInteger calls=new AtomicInteger();server.createContext("/chat/completions",exchange->{try{calls.incrementAndGet();exchange.sendResponseHeaders(status,-1);}finally{exchange.close();}});server.start();
            try{assertThrows(JavaAgentRunner.Failure.class,()->new JavaAgentRunner.HttpTransport(base(server),"synthetic-key",json).complete(Collections.emptyMap(),Duration.ofSeconds(5)));assertEquals(1,calls.get(),"HTTP "+status);}
            finally{server.stop(0);}
        }
    }
    private final ObjectMapper json=new ObjectMapper();
    private HttpServer server()throws Exception{return HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);}
    private String base(HttpServer server){return "http://127.0.0.1:"+server.getAddress().getPort();}
    @Test void brokenConnectionCanRecoverAndExhaustionKeepsTransportCategory()throws Exception{
        for(boolean recover:Arrays.asList(false,true)){
            HttpServer server=server();AtomicInteger calls=new AtomicInteger();
            server.createContext("/chat/completions",exchange->{
                try{
                    if(calls.incrementAndGet()==2 && recover){
                        byte[] body="{}".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
                    }
                }finally{exchange.close();}
            });
            server.start();
            try{
                JavaAgentRunner.HttpTransport transport=new JavaAgentRunner.HttpTransport(base(server),"test",json);
                if(recover){transport.complete(Collections.emptyMap(),Duration.ofSeconds(5));assertEquals(2,calls.get());}
                else{assertEquals("PROVIDER_CONNECTION",assertThrows(JavaAgentRunner.Failure.class,
                    ()->transport.complete(Collections.emptyMap(),Duration.ofSeconds(5))).code);assertEquals(3,calls.get());}
            }finally{server.stop(0);}
        }
    }
    @Test void retriesServerFailuresTwiceButNeverRetriesUnauthorized()throws Exception{
        for(int status:new int[]{503,401}){
            HttpServer server=server();AtomicInteger count=new AtomicInteger();
            server.createContext("/chat/completions",exchange->{try{
                int n=count.incrementAndGet();byte[] body="{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(n==3?200:status,body.length);exchange.getResponseBody().write(body);
            }finally{exchange.close();}});server.start();
            try{
                JavaAgentRunner.HttpTransport transport=new JavaAgentRunner.HttpTransport(base(server),"test-key",json);
                if(status==503){transport.complete(Collections.emptyMap(),Duration.ofSeconds(3));assertEquals(3,count.get());}
                else{assertThrows(JavaAgentRunner.Failure.class,()->transport.complete(Collections.emptyMap(),Duration.ofSeconds(3)));assertEquals(1,count.get());}
            }finally{server.stop(0);}
        }
    }
    @Test void realHttpWireUsesExplicitKeyAndUtf8AndParsesToolCalls()throws Exception{
        HttpServer server=server();AtomicReference<String> auth=new AtomicReference<>(),body=new AtomicReference<>();
        server.createContext("/chat/completions",exchange->{try{auth.set(exchange.getRequestHeaders().getFirst("Authorization"));body.set(new String(cn.bidcheck.compat.Java8Api.readAllBytes(exchange.getRequestBody()),StandardCharsets.UTF_8));byte[] response="{\"choices\":[{\"message\":{\"tool_calls\":[]}}]}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);}finally{exchange.close();}});server.start();
        try{Map<String,Object> result=new JavaAgentRunner.HttpTransport(base(server),"only-explicit-test-key",json).complete(Collections.<String,Object>singletonMap("model","中文模型"),Duration.ofSeconds(3));assertEquals("Bearer only-explicit-test-key",auth.get());assertTrue(body.get().contains("中文模型"));assertTrue(result.get("choices") instanceof List);}finally{server.stop(0);}
    }
    @Test void providerRedirectCannotForwardBearerCredentials()throws Exception{
        HttpServer server=server();AtomicInteger leaks=new AtomicInteger();server.createContext("/chat/completions",exchange->{exchange.getResponseHeaders().add("Location",base(server)+"/leak");exchange.sendResponseHeaders(302,-1);exchange.close();});server.createContext("/leak",exchange->{leaks.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});server.start();
        try{JavaAgentRunner.Failure failure=assertThrows(JavaAgentRunner.Failure.class,()->new JavaAgentRunner.HttpTransport(base(server),"test-key",json).complete(Collections.emptyMap(),Duration.ofSeconds(3)));assertEquals("MODEL_OR_SDK_ERROR",failure.code);assertEquals(0,leaks.get());}finally{server.stop(0);}
    }
    @Test void oversizedProviderBodyIsBoundedAndCannotBecomeFacts()throws Exception{
        HttpServer server=server();server.createContext("/chat/completions",exchange->{try{byte[] response=new byte[4*1024*1024+1];Arrays.fill(response,(byte)'x');exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);}catch(java.io.IOException clientStopped){}finally{exchange.close();}});server.start();
        try{assertThrows(Exception.class,()->new JavaAgentRunner.HttpTransport(base(server),"test-key",json).complete(Collections.emptyMap(),Duration.ofSeconds(3)));}finally{server.stop(0);}
    }
    @Test void cancellationInterruptsOutstandingProviderRequest()throws Exception{
        HttpServer server=server();CountDownLatch requested=new CountDownLatch(1),release=new CountDownLatch(1),cancelled=new CountDownLatch(1);server.createContext("/chat/completions",exchange->{requested.countDown();try{release.await(5,TimeUnit.SECONDS);exchange.sendResponseHeaders(200,-1);}catch(Exception ignored){}finally{exchange.close();}});server.start();
        Thread calling=new Thread(()->{try{new JavaAgentRunner.HttpTransport(base(server),"test-key",json).complete(Collections.emptyMap(),Duration.ofSeconds(10));}catch(InterruptedException expected){cancelled.countDown();}catch(Exception unexpected){}});
        try{calling.start();assertTrue(requested.await(3,TimeUnit.SECONDS));calling.interrupt();assertTrue(cancelled.await(2,TimeUnit.SECONDS));}finally{release.countDown();calling.interrupt();calling.join(2000);server.stop(0);}
    }
    @Test void jdk8SocksProxySelectorCannotInterceptExplicitDirectAgentSocket()throws Exception{
        HttpServer server=server();AtomicInteger received=new AtomicInteger(),selected=new AtomicInteger();
        server.createContext("/chat/completions",exchange->{try{received.incrementAndGet();byte[] response="{\"choices\":[]}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);}finally{exchange.close();}});server.start();
        ProxySelector previous=ProxySelector.getDefault();
        try{
            ProxySelector.setDefault(new ProxySelector(){
                public List<Proxy> select(URI uri){if(uri.getPort()==server.getAddress().getPort())selected.incrementAndGet();return Collections.singletonList(new Proxy(Proxy.Type.SOCKS,new InetSocketAddress("127.0.0.1",9)));}
                public void connectFailed(URI uri,java.net.SocketAddress address,java.io.IOException failure){}
            });
            Map<String,Object> response=new JavaAgentRunner.HttpTransport(base(server),"direct-socket-test-key",json).complete(Collections.emptyMap(),Duration.ofSeconds(3));
            assertTrue(response.containsKey("choices"));assertEquals(1,received.get());assertEquals(0,selected.get(),"Neither HTTP nor underlying JDK8 socket may consult a SOCKS selector.");
        }finally{ProxySelector.setDefault(previous);server.stop(0);}
    }
    @Test void absoluteRequestDeadlineStopsSlowProvider()throws Exception{
        HttpServer server=server();CountDownLatch release=new CountDownLatch(1);server.createContext("/chat/completions",exchange->{try{release.await(5,TimeUnit.SECONDS);exchange.sendResponseHeaders(200,-1);}catch(Exception ignored){}finally{exchange.close();}});server.start();
        long began=System.nanoTime();try{assertThrows(Exception.class,()->new JavaAgentRunner.HttpTransport(base(server),"test-key",json).complete(Collections.emptyMap(),Duration.ofMillis(150)));assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)<2000);}finally{release.countDown();server.stop(0);}
    }
}
