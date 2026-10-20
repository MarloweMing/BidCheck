package cn.bidcheck.ocr;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class OcrTrafficControlTest {
    HttpServer server;ExecutorService handlers;URI endpoint;
    final AtomicInteger calls=new AtomicInteger(),status=new AtomicInteger(200);
    final List<Long> arrivals=new CopyOnWriteArrayList<>();
    volatile CountDownLatch entered,release;
    @BeforeEach void setup()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);handlers=Executors.newCachedThreadPool();server.setExecutor(handlers);
        server.createContext("/",exchange->{calls.incrementAndGet();arrivals.add(System.nanoTime());
            try{if(entered!=null)entered.countDown();if(release!=null)release.await(2,TimeUnit.SECONDS);exchange.sendResponseHeaders(status.get(),2);exchange.getResponseBody().write("{}".getBytes());}
            catch(InterruptedException stopped){Thread.currentThread().interrupt();}finally{exchange.close();}});
        server.start();endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");
    }
    @AfterEach void close(){if(release!=null)release.countDown();server.stop(0);handlers.shutdownNow();}
    void send(OcrRequestGovernor g,OcrRequestSettings s)throws Exception {g.send(HttpClient.newHttpClient(),endpoint,"test","{}".getBytes(),1024,Duration.ofSeconds(2),s);}
    @Test void tokenBucketPacesRealRequestsEvenWhenConcurrencyIsAvailable()throws Exception {
        var settings=new OcrRequestSettings(16,1,1,2,600,1,5,100);
        var governor=OcrRequestGovernor.forService(endpoint,"ocr-rate","test",4,settings);
        ExecutorService workers=Executors.newFixedThreadPool(3);
        try{List<Future<?>> pending=new ArrayList<>();for(int i=0;i<3;i++)pending.add(workers.submit(()->{try{send(governor,settings);}catch(Exception failure){throw new CompletionException(failure);}}));
            for(var item:pending)item.get(3,TimeUnit.SECONDS);
            assertThat(calls.get()).isEqualTo(3);assertThat(TimeUnit.NANOSECONDS.toMillis(arrivals.get(2)-arrivals.get(0))).isGreaterThanOrEqualTo(150);
        }finally{workers.shutdownNow();}
    }
    @Test void circuitStopsTrafficThenAllowsOneRecoveryProbe()throws Exception {
        var settings=new OcrRequestSettings(16,1,1,2,0,8,2,100);
        var governor=OcrRequestGovernor.forService(endpoint,"ocr-circuit","test",4,settings);status.set(502);
        for(int i=0;i<2;i++)assertThatThrownBy(()->send(governor,settings)).isInstanceOf(OcrFailure.class);
        assertThatThrownBy(()->send(governor,settings)).isInstanceOfSatisfying(OcrFailure.class,e->assertThat(e.code()).isEqualTo("CIRCUIT_OPEN"));
        assertThat(calls.get()).isEqualTo(2);Thread.sleep(130);
        entered=new CountDownLatch(1);release=new CountDownLatch(1);status.set(200);ExecutorService worker=Executors.newSingleThreadExecutor();
        try{Future<?> probe=worker.submit(()->{try{send(governor,settings);}catch(Exception e){throw new CompletionException(e);}});
            assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->send(governor,settings)).isInstanceOfSatisfying(OcrFailure.class,e->assertThat(e.code()).isEqualTo("CIRCUIT_OPEN"));
            assertThat(calls.get()).isEqualTo(3);release.countDown();probe.get(2,TimeUnit.SECONDS);send(governor,settings);assertThat(calls.get()).isEqualTo(4);
        }finally{release.countDown();worker.shutdownNow();}
    }

    @Test void latePreCircuitSuccessCannotClearOpenCircuitOrConcurrentProbe()throws Exception {
        CountDownLatch oldEntered=new CountDownLatch(2),oldOne=new CountDownLatch(1),oldTwo=new CountDownLatch(1),probeEntered=new CountDownLatch(1),probeRelease=new CountDownLatch(1);
        AtomicInteger actual=new AtomicInteger();
        server.createContext("/epoch",exchange->{actual.incrementAndGet();String kind=new String(exchange.getRequestBody().readAllBytes());
            try{
                if(kind.startsWith("old")){oldEntered.countDown();(kind.equals("old1")?oldOne:oldTwo).await(3,TimeUnit.SECONDS);}
                if(kind.equals("probe")){probeEntered.countDown();probeRelease.await(3,TimeUnit.SECONDS);}
                exchange.sendResponseHeaders(kind.equals("fail")?502:200,2);exchange.getResponseBody().write("{}".getBytes());
            }catch(InterruptedException stopped){Thread.currentThread().interrupt();}finally{exchange.close();}});
        URI epochEndpoint=endpoint.resolve("/epoch");var settings=new OcrRequestSettings(16,1,1,2,0,8,2,350);
        var governor=OcrRequestGovernor.forService(epochEndpoint,"ocr-health-epoch","test",4,settings);
        var client=HttpClient.newHttpClient();ExecutorService workers=Executors.newFixedThreadPool(3);
        java.util.function.Consumer<String> request=kind->{try{governor.send(client,epochEndpoint,"test",kind.getBytes(),1024,Duration.ofSeconds(2),settings);}catch(Exception e){throw new CompletionException(e);}};
        try{
            Future<?> a=workers.submit(()->request.accept("old1")),b=workers.submit(()->request.accept("old2"));assertThat(oldEntered.await(1,TimeUnit.SECONDS)).isTrue();
            for(int i=0;i<2;i++)assertThatThrownBy(()->request.accept("fail")).hasCauseInstanceOf(OcrFailure.class);
            oldOne.countDown();a.get(1,TimeUnit.SECONDS);
            assertThatThrownBy(()->request.accept("new")).satisfies(e->assertThat(((OcrFailure)e.getCause()).code()).isEqualTo("CIRCUIT_OPEN"));
            assertThat(actual.get()).isEqualTo(4);Thread.sleep(400);
            Future<?> probe=workers.submit(()->request.accept("probe"));assertThat(probeEntered.await(1,TimeUnit.SECONDS)).isTrue();
            oldTwo.countDown();b.get(1,TimeUnit.SECONDS);
            assertThatThrownBy(()->request.accept("new")).satisfies(e->assertThat(((OcrFailure)e.getCause()).code()).isEqualTo("CIRCUIT_OPEN"));
            assertThat(actual.get()).isEqualTo(5);probeRelease.countDown();probe.get(1,TimeUnit.SECONDS);request.accept("new");assertThat(actual.get()).isEqualTo(6);
        }finally{oldOne.countDown();oldTwo.countDown();probeRelease.countDown();workers.shutdownNow();}
    }

    @Test void treeBudgetCountsEveryActualHttpAttemptInsideProviderRetries()throws Exception {
        var settings=new OcrRequestSettings(16,3,1,2,0,8,100,100);status.set(502);
        var governor=OcrRequestGovernor.forService(endpoint,"ocr-tree-budget","test",4,settings);
        var budget=new OcrRequestGovernor.RequestBudget(2,Duration.ofSeconds(2));
        assertThatThrownBy(()->governor.send(HttpClient.newHttpClient(),endpoint,"test","{}".getBytes(),1024,Duration.ofSeconds(1),settings,budget))
            .isInstanceOfSatisfying(OcrFailure.class,e->assertThat(e.code()).isEqualTo("BATCH_BUDGET_EXHAUSTED"));
        assertThat(calls.get()).isEqualTo(2);
    }
}
