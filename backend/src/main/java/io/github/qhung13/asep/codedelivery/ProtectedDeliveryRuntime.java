package io.github.qhung13.asep.codedelivery;

import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;
import javax.net.ssl.SSLContext;
import javax.sql.DataSource;

/** Installed single-host service capability, never a Spring bean or agent API.
 * No environment overrides, credential discovery, development endpoint fallback,
 * configuration writer or live default. Root installation is an Owner action.
 */
final class ProtectedDeliveryRuntime {
    static final String RUNTIME_IDENTITY_SQL="SELECT session_user=current_user AND session_user='asep_delivery_runtime' AND NOT(rolsuper OR rolcreaterole OR rolcreatedb OR rolbypassrls) AND NOT pg_has_role(session_user,'asep_delivery_issuer','USAGE') AND NOT has_schema_privilege(session_user,'code_delivery','CREATE') AND NOT has_table_privilege(session_user,'code_delivery.owner_grants','INSERT') AND NOT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname='orchestration' AND has_schema_privilege(session_user,oid,'USAGE')) FROM pg_roles WHERE rolname=session_user";
    static final Path JAR=Path.of("/opt/asep/delivery/asep-backend.jar");
    record Settings(String role,long serviceUid,long agentUid,long controllerUid,long operatorUid,long executorUid,long orchestratorUid,
        String installedJarSha256,String goOrigin,String goServerCertificateSha256,Path clientCertificate,Path clientKey,Path authorityCa,
        Path evidenceRoot,Path baseObjectsRoot,Path verifierJobsRoot,String profileSha256,String harnessSha256,String imageSha256,
        int minimumTests,long maximumReceiptAgeSeconds,String databaseUrl,Path databasePassword,Path databaseCa,
        Path readCredential,Path writeCredential,Path enrollmentRoot,boolean liveWritesEnabled) {
        TestProjectDeliveryPreparation.Profile profile(){return new TestProjectDeliveryPreparation.Profile(profileSha256,harnessSha256,imageSha256,minimumTests,Duration.ofSeconds(maximumReceiptAgeSeconds));}
    }
    static Settings authenticate(String role){
        try{
            if(!Set.of("candidate-controller","delivery-executor").contains(role))throw denied();
            Path config=Path.of("/etc/asep/"+role+".json");ProtectedOperatorFiles.hierarchy(config.getParent(),0);
            var c=JdbcDeliveryLedger.JSON.readValue(ProtectedOperatorFiles.read(config.getParent(),config,0,16384,false),Settings.class);
            validate(c,role);
            String status=Files.readString(Path.of("/proc/self/status"));
            if(!RestrictedOperatorRuntime.uidsMatch(status,c.serviceUid(),c.agentUid()))throw denied();
            String location=DeliveryRuntimeCommand.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm();
            if(!location.equals("jar:nested:"+JAR+"/!BOOT-INF/classes/!/")&&!location.equals(JAR.toUri().toString()))throw denied();
            ProtectedOperatorFiles.hierarchy(JAR.getParent(),0);
            if(!ControllerEvidenceStore.digest(ProtectedOperatorFiles.read(JAR.getParent(),JAR,0,134217728,false)).equals(c.installedJarSha256()))throw denied();
            ProtectedOperatorFiles.hierarchy(c.evidenceRoot(),c.controllerUid());
            c.profile();return c;
        }catch(Exception invalid){throw denied();}
    }
    static void validate(Settings c,String role){
        if(c==null||!role.equals(c.role())||c.installedJarSha256()==null||!c.installedJarSha256().matches("[0-9a-f]{64}")
            ||c.goOrigin()==null||!c.goOrigin().matches("https://127\\.0\\.0\\.1:[1-9][0-9]{0,4}")||c.goServerCertificateSha256()==null||!c.goServerCertificateSha256().matches("[0-9a-f]{64}"))throw denied();
        if(URI.create(c.goOrigin()).getPort()>65535)throw denied();
        var ids=new HashSet<Long>();for(long id:new long[]{c.agentUid(),c.controllerUid(),c.operatorUid(),c.executorUid(),c.orchestratorUid()})if(id<=0||id==65534||!ids.add(id))throw denied();
        if(c.minimumTests()<2||c.maximumReceiptAgeSeconds()<1||c.maximumReceiptAgeSeconds()>3600)throw denied();
        if(c.serviceUid()!=(role.equals("candidate-controller")?c.controllerUid():c.executorUid()))throw denied();
        for(Path path:new Path[]{c.clientCertificate(),c.clientKey(),c.authorityCa(),c.evidenceRoot()})absolute(path);
        if(role.equals("candidate-controller")){absolute(c.baseObjectsRoot());absolute(c.verifierJobsRoot());if(!c.installedJarSha256().equals(c.harnessSha256()))throw denied();if(c.liveWritesEnabled()||c.databasePassword()!=null||c.readCredential()!=null||c.writeCredential()!=null)throw denied();}
        else {
            for(Path path:new Path[]{c.databasePassword(),c.databaseCa(),c.readCredential(),c.writeCredential(),c.enrollmentRoot()})absolute(path);
            if(c.databaseUrl()==null||!c.databaseUrl().matches("jdbc:postgresql://127\\.0\\.0\\.1:[1-9][0-9]{0,4}/[a-z][a-z0-9_]{0,62}"))throw denied();
            if(c.readCredential().equals(c.writeCredential()))throw denied();
        }
        c.profile();
    }
    private static void absolute(Path path){if(path==null||!path.isAbsolute()||!path.normalize().equals(path))throw denied();}
    static byte[] file(Path path,long uid,int limit,boolean secret){ProtectedOperatorFiles.hierarchy(path.getParent(),uid);return ProtectedOperatorFiles.read(path.getParent(),path,uid,limit,secret);}
    static SSLContext identity(Settings c){
        byte[] key=file(c.clientKey(),c.serviceUid(),16384,true);
        try{return DeliveryTlsIdentity.context(file(c.clientCertificate(),0,65536,false),key,file(c.authorityCa(),0,65536,false),"spiffe://asep.local/"+(c.role().equals("candidate-controller")?"candidate-controller":"code-delivery"));}
        finally{Arrays.fill(key,(byte)0);}
    }
    static String credential(Path path,long uid,String expectedDigest){
        byte[] body=file(path,uid,4096,true);try{
            if(expectedDigest!=null&&!ControllerEvidenceStore.digest(body).equals(expectedDigest))throw denied();
            String value=new String(body,StandardCharsets.US_ASCII);if(value.endsWith("\n"))value=value.substring(0,value.length()-1);
            if(value.isEmpty()||value.chars().anyMatch(ch->ch<33||ch>126))throw denied();return value;
        }finally{Arrays.fill(body,(byte)0);}
    }
    static DataSource database(Settings c){return new RuntimeDatabase(c);}
    private static final class RuntimeDatabase implements DataSource {
        private final Settings c;RuntimeDatabase(Settings c){this.c=c;}
        public Connection getConnection()throws SQLException{
            String password=credential(c.databasePassword(),c.serviceUid(),null);
            file(c.databaseCa(),0,65536,false);
            var properties=new Properties();properties.setProperty("user","asep_delivery_runtime");properties.setProperty("password",password);
            properties.setProperty("sslmode","verify-full");properties.setProperty("sslrootcert",c.databaseCa().toString());properties.setProperty("connectTimeout","3");properties.setProperty("socketTimeout","5");properties.setProperty("options","-c statement_timeout=4000 -c lock_timeout=3000");
            Connection db=DriverManager.getConnection(c.databaseUrl(),properties);
            try(var query=db.createStatement();var row=query.executeQuery(RUNTIME_IDENTITY_SQL)){
                if(!row.next()||!row.getBoolean(1)){db.close();throw new SQLException("Protected runtime database denied","42501");}
            }catch(SQLException denied){db.close();throw new SQLException("Protected runtime database unavailable","08001");}return db;
        }
        public Connection getConnection(String user,String password)throws SQLException{throw new SQLFeatureNotSupportedException();}
        public PrintWriter getLogWriter(){return null;}public void setLogWriter(PrintWriter writer)throws SQLException{throw new SQLFeatureNotSupportedException();}
        public void setLoginTimeout(int seconds){}public int getLoginTimeout(){return 3;}public Logger getParentLogger()throws SQLFeatureNotSupportedException{throw new SQLFeatureNotSupportedException();}
        public <T>T unwrap(Class<T> type)throws SQLException{throw new SQLFeatureNotSupportedException();}public boolean isWrapperFor(Class<?> type){return false;}
    }
    static IllegalStateException denied(){return new IllegalStateException("Protected delivery deployment unavailable or denied");}
}
