package io.github.qhung13.asep.codedelivery;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static io.github.qhung13.asep.codedelivery.TestProjectDeliveryPreparation.*;

/** Offline GitLab API plus real native git-receive-pack, never a GitLab endpoint. */
final class NativeGitLabFixture implements AutoCloseable {
    final Path bare;final HttpServer server;final java.util.concurrent.ExecutorService handlers=Executors.newCachedThreadPool();
    final AtomicReference<Throwable> errors=new AtomicReference<>();final AtomicInteger pushes=new AtomicInteger(),creates=new AtomicInteger(),forbidden=new AtomicInteger();
    final List<Map<String,Object>> mrs=new CopyOnWriteArrayList<>();
    Runnable beforePush=()->{},beforeMr=()->{};boolean losePush,loseMr,incompleteMrs,wrongProject,unreadable;int mrStatus=201;
    final byte[] base;final String baseSha,baseTree;
    NativeGitLabFixture(Path directory)throws Exception{
        Files.createDirectories(directory);bare=directory.resolve("repo.git");
        git(null,"init","--bare","--initial-branch=main",bare.toString());
        baseTree=gitId(ObjectType.TREE,new byte[0]);base=("tree "+baseTree+"\nauthor Base <base@invalid> 1793145600 +0000\ncommitter Base <base@invalid> 1793145600 +0000\n\nBase\n").getBytes(StandardCharsets.US_ASCII);baseSha=gitId(ObjectType.COMMIT,base);
        put(ObjectType.TREE,new byte[0]);put(ObjectType.COMMIT,base);move("main",baseSha);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(handlers);
        server.createContext("/",exchange->{
            try{
                String path=exchange.getRequestURI().getRawPath(),method=exchange.getRequestMethod();
                if(unreadable&&method.equals("GET")){exchange.sendResponseHeaders(403,-1);return;}
                if(method.equals("GET")&&path.endsWith("/info/refs")){
                    byte[] advertisement=git(null,"--git-dir="+bare,"receive-pack","--stateless-rpc","--advertise-refs",bare.toString());
                    var out=new ByteArrayOutputStream();out.writeBytes(GitReceivePack.packet("# service=git-receive-pack\n"));out.writeBytes("0000".getBytes(StandardCharsets.US_ASCII));out.writeBytes(advertisement);
                    exchange.getResponseHeaders().set("Content-Type","application/x-git-receive-pack-advertisement");send(exchange,200,out.toByteArray());return;
                }
                if(method.equals("POST")&&path.endsWith("/git-receive-pack")){
                    pushes.incrementAndGet();byte[] body=exchange.getRequestBody().readNBytes(2097153);if(body.length>2097152)throw new AssertionError("oversize push");beforePush.run();
                    byte[] result=git(body,"--git-dir="+bare,"receive-pack","--stateless-rpc",bare.toString());if(losePush){exchange.close();return;}
                    exchange.getResponseHeaders().set("Content-Type","application/x-git-receive-pack-result");send(exchange,200,result);return;
                }
                exchange.getResponseHeaders().set("Content-Type","application/json");
                if(method.equals("GET")&&path.equals("/api/v4/projects/87786140")){
                    send(exchange,200,json(Map.of("id",wrongProject?42:87786140,"path_with_namespace","qhung-13/asep-m01-sandbox","default_branch","main","archived",false)));return;
                }
                if(method.equals("GET")&&path.startsWith("/api/v4/projects/87786140/repository/branches/")){
                    String branch=java.net.URLDecoder.decode(path.substring(path.lastIndexOf('/')+1),StandardCharsets.UTF_8);
                    String head=head(branch);if(head==null){exchange.sendResponseHeaders(404,-1);return;}
                    send(exchange,200,json(Map.of("name",branch,"protected",branch.equals("main"),"can_push",!branch.equals("main"),"commit",Map.of("id",head))));return;
                }
                if(path.equals("/api/v4/projects/87786140/merge_requests")){
                    if(method.equals("GET")){
                        var headers=exchange.getResponseHeaders();headers.set("X-Page","1");headers.set("X-Per-Page","100");headers.set("X-Total",Integer.toString(mrs.size()));headers.set("X-Total-Pages","1");headers.set("X-Next-Page","");
                        if(incompleteMrs)headers.set("Link","<https://not-followed.invalid/>; rel=\"next\"");send(exchange,200,json(mrs));return;
                    }
                    if(method.equals("POST")){
                        creates.incrementAndGet();if(mrStatus!=201){exchange.sendResponseHeaders(mrStatus,-1);return;}
                        var body=JdbcDeliveryLedger.JSON.readTree(exchange.getRequestBody().readNBytes(8193));beforeMr.run();String source=body.get("source_branch").asString();
                        var result=new java.util.HashMap<String,Object>();result.put("project_id",87786140);result.put("source_project_id",87786140);result.put("target_project_id",87786140);result.put("iid",mrs.size()+1);
                        result.put("source_branch",source);result.put("target_branch",body.get("target_branch").asString());result.put("sha",head(source));result.put("draft",true);result.put("state","opened");
                        result.put("title",body.get("title").asString());result.put("description",body.get("description").asString());mrs.add(result);
                        if(loseMr){exchange.close();return;}send(exchange,201,json(result));return;
                    }
                }
                forbidden.incrementAndGet();exchange.sendResponseHeaders(400,-1);
            }catch(Throwable unexpected){errors.compareAndSet(null,unexpected);try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){}}
            finally{exchange.close();}
        });server.start();
    }
    URI origin(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort());}
    Artifacts baseSource(){return(type,sha,bound)->type==ObjectType.COMMIT?base.clone():new byte[0];}
    String put(ObjectType type,byte[] bytes)throws Exception{return new String(git(bytes,"--git-dir="+bare,"hash-object","-w","-t",type.name().toLowerCase(java.util.Locale.ROOT),"--stdin"),StandardCharsets.US_ASCII).strip();}
    void move(String branch,String sha){try{git(null,"--git-dir="+bare,"update-ref","refs/heads/"+branch,sha);}catch(Exception e){throw new IllegalStateException("fixture ref change failed");}}
    String head(String branch){try{return new String(git(null,"--git-dir="+bare,"rev-parse","--verify","refs/heads/"+branch),StandardCharsets.US_ASCII).strip();}catch(Exception absent){return null;}}
    static byte[] git(byte[] input,String...args)throws Exception{
        var command=new ArrayList<String>(List.of("/usr/bin/git","-c","core.hooksPath=/dev/null","-c","receive.fsckObjects=true"));command.addAll(List.of(args));
        var builder=new ProcessBuilder(command);builder.environment().clear();builder.environment().put("PATH","/usr/bin:/bin");builder.environment().put("GIT_CONFIG_NOSYSTEM","1");builder.environment().put("GIT_CONFIG_GLOBAL","/dev/null");builder.environment().put("LANG","C.UTF-8");builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        var process=builder.start();try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var output=executor.submit(()->process.getInputStream().readNBytes(2097153));
            try{try(var stdin=process.getOutputStream()){if(input!=null)stdin.write(input);}if(!process.waitFor(5,TimeUnit.SECONDS))throw new IllegalStateException("git fixture timeout");byte[] bytes=output.get(1,TimeUnit.SECONDS);if(bytes.length>2097152||process.exitValue()!=0)throw new IllegalStateException("git fixture rejected");return bytes;}
            finally{process.destroyForcibly();process.waitFor(2,TimeUnit.SECONDS);output.cancel(true);}
        }
    }
    private static byte[] json(Object value){return JdbcDeliveryLedger.JSON.writeValueAsBytes(value);}
    private static void send(com.sun.net.httpserver.HttpExchange exchange,int status,byte[] body)throws Exception{exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);}
    @Override public void close()throws Exception{server.stop(0);handlers.shutdown();if(!handlers.awaitTermination(5,TimeUnit.SECONDS)){handlers.shutdownNow();throw new IllegalStateException("fixture handlers unfinished");}if(errors.get()!=null)throw new AssertionError("fake server unexpected error",errors.get());}
}
