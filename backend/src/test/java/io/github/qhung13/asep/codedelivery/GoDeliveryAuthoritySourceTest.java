package io.github.qhung13.asep.codedelivery;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Ephemeral self-signed fixture identities, not enrollment or production credentials. */
class GoDeliveryAuthoritySourceTest {
    @TempDir Path temporary;
    private static final char[] FIXTURE_PASSWORD="fixture-only".toCharArray();
    private KeyStore identity(String name,String san) throws Exception {
        Path file=temporary.resolve(name+".p12");
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),
                "-genkeypair","-alias",name,"-keyalg","EC","-groupname","secp256r1","-dname","CN=fixture-"+name,
                "-ext","SAN="+san,"-validity","1","-storetype","PKCS12","-keystore",file.toString(),"-storepass","fixture-only","-noprompt")
                .redirectErrorStream(true).redirectOutput(temporary.resolve(name+".log").toFile()).start();
        boolean finished=process.waitFor(10,TimeUnit.SECONDS);
        if(!finished) { process.destroyForcibly();assertTrue(process.waitFor(5,TimeUnit.SECONDS)); }
        assertTrue(finished);assertEquals(0,process.exitValue());
        var store=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(file)){store.load(input,FIXTURE_PASSWORD);}return store;
    }
    private SSLContext context(KeyStore identity,Certificate trusted) throws Exception {
        var trust=KeyStore.getInstance("PKCS12");trust.load(null,null);trust.setCertificateEntry("peer",trusted);
        var keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keys.init(identity,FIXTURE_PASSWORD);
        var manager=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());manager.init(trust);
        var context=SSLContext.getInstance("TLSv1.3");context.init(keys.getKeyManagers(),manager.getTrustManagers(),null);return context;
    }
    @Test void mutualTlsPinStrictShapeAndEntireBodyDeadline() throws Exception {
        var serverIdentity=identity("server","ip:127.0.0.1");var clientIdentity=identity("client","uri:spiffe://asep.local/code-delivery");
        var serverContext=context(serverIdentity,clientIdentity.getCertificate("client"));
        var clientContext=context(clientIdentity,serverIdentity.getCertificate("server"));
        String pin=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serverIdentity.getCertificate("server").getEncoded()));
        UUID operation=UUID.randomUUID(),run=UUID.randomUUID(),attempt=UUID.randomUUID();
        var now=Instant.now();
        var snapshot=new TestProjectCurrentPolicyVerifier.Snapshot(run,attempt,9,"DELIVERY_PENDING","policy",1,87786140,"main","a".repeat(40),"a".repeat(40),true,now,operation,"d".repeat(64),now.plusSeconds(30));
        var body=new AtomicReference<>(JdbcDeliveryLedger.JSON.writeValueAsBytes(snapshot));
        var mode=new AtomicReference<>("normal");var requests=new AtomicInteger();var errors=new AtomicReference<Throwable>();
        var release=new CountDownLatch(1);var completed=new CountDownLatch(1);var stalled=new AtomicBoolean();
        var executor=Executors.newCachedThreadPool();
        var server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext){@Override public void configure(com.sun.net.httpserver.HttpsParameters p){var settings=serverContext.getDefaultSSLParameters();settings.setProtocols(new String[]{"TLSv1.3"});settings.setNeedClientAuth(true);p.setSSLParameters(settings);}});
        server.setExecutor(executor);
        String path="/internal/v1/delivery-authority/"+operation+"/"+run+"/"+attempt;
        server.createContext("/",exchange->{
            requests.incrementAndGet();
            try {
                assertEquals(path,exchange.getRequestURI().toString());assertEquals("GET",exchange.getRequestMethod());
                assertEquals("CN=fixture-client",((com.sun.net.httpserver.HttpsExchange)exchange).getSSLSession().getPeerPrincipal().getName());
                exchange.getResponseHeaders().set("Content-Type","application/json");
                if(mode.get().equals("redirect")){exchange.getResponseHeaders().set("Location","https://example.invalid/");exchange.sendResponseHeaders(302,-1);return;}
                if(mode.get().equals("stall")){
                    stalled.set(true);
                    exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write('{');exchange.getResponseBody().flush();
                    assertTrue(release.await(5,TimeUnit.SECONDS));return;
                }
                byte[] selected=body.get();exchange.sendResponseHeaders(200,selected.length);exchange.getResponseBody().write(selected);
            } catch(Throwable unexpected){errors.compareAndSet(null,unexpected);}
            finally {exchange.close();if(mode.get().equals("stall"))completed.countDown();}
        });
        server.start();var origin=URI.create("https://127.0.0.1:"+server.getAddress().getPort());
        try(var source=new GoDeliveryAuthoritySource(origin,operation,clientContext,pin,Duration.ofSeconds(2))){
            assertEquals(snapshot,source.read(run,attempt));
            body.set("[]".getBytes(StandardCharsets.US_ASCII));assertThrows(IllegalStateException.class,()->source.read(run,attempt));
            body.set((JdbcDeliveryLedger.JSON.writeValueAsString(snapshot).replace("\"runId\":","\"unknown\":")).getBytes(StandardCharsets.UTF_8));
            assertThrows(IllegalStateException.class,()->source.read(run,attempt));
            body.set(new byte[8193]);assertThrows(IllegalStateException.class,()->source.read(run,attempt));
            mode.set("redirect");assertThrows(IllegalStateException.class,()->source.read(run,attempt));
            try(var wrongPin=new GoDeliveryAuthoritySource(origin,operation,clientContext,"0".repeat(64),Duration.ofSeconds(2))){
                mode.set("normal");body.set(JdbcDeliveryLedger.JSON.writeValueAsBytes(snapshot));assertThrows(IllegalStateException.class,()->wrongPin.read(run,attempt));
            }
            mode.set("stall");long started=System.nanoTime();assertThrows(IllegalStateException.class,()->source.read(run,attempt));
            assertTrue(Duration.ofNanos(System.nanoTime()-started).compareTo(Duration.ofSeconds(4))<0);
        } finally {
            release.countDown();
            try { if(stalled.get()) assertTrue(completed.await(5,TimeUnit.SECONDS)); }
            finally { server.stop(0);executor.shutdown();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS)); }
        }
        assertEquals(7,requests.get());assertNull(errors.get());
    }
    @Test void messageAckMustBeExplicitAndNoMessageIsOnlyValidForClaim()throws Exception{
        var serverIdentity=identity("ack-server","ip:127.0.0.1");var clientIdentity=identity("ack-client","uri:spiffe://asep.local/code-delivery");
        var serverContext=context(serverIdentity,clientIdentity.getCertificate("ack-client"));var clientContext=context(clientIdentity,serverIdentity.getCertificate("ack-server"));
        String pin=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serverIdentity.getCertificate("ack-server").getEncoded()));
        var body=new AtomicReference<>("{\"accepted\":true}");var status=new AtomicInteger(200);var errors=new AtomicReference<Throwable>();var requests=new AtomicInteger();
        var executor=Executors.newCachedThreadPool();var server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext){@Override public void configure(com.sun.net.httpserver.HttpsParameters p){var settings=serverContext.getDefaultSSLParameters();settings.setProtocols(new String[]{"TLSv1.3"});settings.setNeedClientAuth(true);p.setSSLParameters(settings);}});
        server.createContext("/",exchange->{try{
            requests.incrementAndGet();assertEquals("POST",exchange.getRequestMethod());assertTrue(exchange.getRequestBody().readNBytes(8193).length<=8192);
            exchange.getResponseHeaders().set("Content-Type","application/json");int code=status.get();if(code==204){exchange.sendResponseHeaders(204,-1);return;}byte[] selected=body.get().getBytes(StandardCharsets.US_ASCII);exchange.sendResponseHeaders(code,selected.length);exchange.getResponseBody().write(selected);
        }catch(Throwable failure){errors.compareAndSet(null,failure);}finally{exchange.close();}});server.start();
        try(var messages=new GoDeliveryMessages(URI.create("https://127.0.0.1:"+server.getAddress().getPort()),clientContext,pin,Duration.ofSeconds(2))){
            UUID message=UUID.randomUUID(),token=UUID.randomUUID();assertDoesNotThrow(()->messages.acknowledge(message,"a".repeat(64),token));
            for(String invalid:new String[]{"{\"accepted\":false}","{}","{\"accepted\":true,\"authority\":true}","[]"}){body.set(invalid);assertThrows(DeliveryException.class,()->messages.acknowledge(message,"a".repeat(64),token));}
            status.set(204);assertThrows(DeliveryException.class,()->messages.acknowledge(message,"a".repeat(64),token));assertNull(messages.claim());
        }finally{server.stop(5);executor.shutdown();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
        assertEquals(7,requests.get());assertNull(errors.get());
    }
    @Test void noInsecureOriginOrUnboundedDeadline() throws Exception {
        var context=SSLContext.getDefault();
        assertThrows(IllegalStateException.class,()->new GoDeliveryAuthoritySource(URI.create("http://127.0.0.1:1234"),UUID.randomUUID(),context,"a".repeat(64),Duration.ofSeconds(1)));
        assertThrows(IllegalStateException.class,()->new GoDeliveryAuthoritySource(URI.create("https://example.com:443"),UUID.randomUUID(),context,"a".repeat(64),Duration.ofSeconds(1)));
        assertThrows(IllegalStateException.class,()->new GoDeliveryAuthoritySource(URI.create("https://127.0.0.1:443"),UUID.randomUUID(),context,"a".repeat(64),Duration.ofSeconds(6)));
    }
}
