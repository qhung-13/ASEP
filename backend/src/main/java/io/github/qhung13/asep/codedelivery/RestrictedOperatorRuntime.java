package io.github.qhung13.asep.codedelivery;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Properties;
import java.util.Set;

/** Fixed root-controlled Linux deployment boundary, not a request-supplied identity provider.
 * The CLI must be installed separately from the agent-writable repository. No alternate
 * config path, UID override, environment secret or successful default exists.
 */
final class RestrictedOperatorRuntime {
    private static final Path CONFIG=Path.of("/etc/asep/owner-approval.properties");
    private static final Path JAR=Path.of("/opt/asep/owner-approval/asep.jar");
    record Policy(long operatorUid,long agentUid,String databaseLogin,String databaseUrl,Path passwordFile,Path databaseCa,
            Path evidenceRoot,long evidenceUid,TestProjectDeliveryPreparation.Profile profile) {}
    private RestrictedOperatorRuntime() {}
    static Policy authenticate() {
        try {
            ProtectedOperatorFiles.hierarchy(CONFIG.getParent(),0);
            byte[] data=ProtectedOperatorFiles.read(CONFIG.getParent(),CONFIG,0,8192,false);
            var properties=new Properties();
            try (var input=new java.io.StringReader(new String(data,StandardCharsets.UTF_8))) { properties.load(input); }
            var allowed=Set.of("operator_uid","agent_uid","database_login","database_url","password_file","database_ca",
                    "evidence_root","evidence_uid","profile_sha256","harness_sha256","image_sha256","minimum_tests","maximum_receipt_age_seconds","installed_jar_sha256");
            if (!properties.stringPropertyNames().equals(allowed)) throw denied();
            long operator=Long.parseLong(properties.getProperty("operator_uid")), agent=Long.parseLong(properties.getProperty("agent_uid")), controller=Long.parseLong(properties.getProperty("evidence_uid"));
            byte[] status;
            try (var stream=Files.newInputStream(Path.of("/proc/self/status"))) { status=stream.readNBytes(8193); }
            if (status.length>8192 || !uidsMatch(new String(status,StandardCharsets.US_ASCII),operator,agent) || controller<0 || controller==agent) throw denied();
            ProtectedOperatorFiles.hierarchy(JAR.getParent(),0);
            String location=OwnerApprovalCommand.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm();
            if (!installedLocation(location)) throw denied();
            // Root-owned installed bytes; dependencies are nested inside this exact reviewed artifact.
            byte[] jar=ProtectedOperatorFiles.read(JAR.getParent(),JAR,0,134217728,false);
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar));
            if (!digest.equals(properties.getProperty("installed_jar_sha256"))) throw denied();
            String login=properties.getProperty("database_login"), url=properties.getProperty("database_url");
            if (!login.matches("[a-z_][a-z0-9_]{0,62}") || login.equals("asep_delivery_runtime")
                    || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[1-9][0-9]{0,4}/[a-z][a-z0-9_]{0,62}")) throw denied();
            Path password=absolute(properties,"password_file"),ca=absolute(properties,"database_ca"),evidence=absolute(properties,"evidence_root");
            ProtectedOperatorFiles.hierarchy(password.getParent(),operator);
            ProtectedOperatorFiles.hierarchy(ca.getParent(),0);
            ProtectedOperatorFiles.read(ca.getParent(),ca,0,65536,false);
            ProtectedOperatorFiles.hierarchy(evidence,controller);
            var profile=new TestProjectDeliveryPreparation.Profile(properties.getProperty("profile_sha256"),properties.getProperty("harness_sha256"),
                    properties.getProperty("image_sha256"),Integer.parseInt(properties.getProperty("minimum_tests")),Duration.ofSeconds(Long.parseLong(properties.getProperty("maximum_receipt_age_seconds"))));
            return new Policy(operator,agent,login,url,password,ca,evidence,controller,profile);
        } catch (Exception unavailable) { throw denied(); }
    }
    static boolean uidsMatch(String kernelStatus,long operator,long agent) {
        if (operator<=0 || agent<0 || operator==agent) return false;
        var lines=kernelStatus.lines().filter(line->line.startsWith("Uid:")).toList();
        if (lines.size()!=1) return false;
        String[] ids=lines.getFirst().substring(4).trim().split("\\s+");
        if (ids.length!=4) return false;
        try { for(String id:ids) if(Long.parseLong(id)!=operator) return false; return true; }
        catch (NumberFormatException malformed) { return false; }
    }
    static boolean installedLocation(String location) {
        if (location.equals("jar:nested:"+JAR+"/!BOOT-INF/classes/!/")) return true;
        try {
            var uri=java.net.URI.create(location);
            return "file".equals(uri.getScheme()) && uri.getRawQuery()==null && uri.getRawFragment()==null && Path.of(uri).equals(JAR);
        } catch (RuntimeException invalid) { return false; }
    }
    private static Path absolute(Properties properties,String name) {
        Path value=Path.of(properties.getProperty(name));
        if (!value.isAbsolute() || !value.normalize().equals(value)) throw denied();
        return value;
    }
    private static IllegalStateException denied() { return new IllegalStateException("Restricted Owner operator deployment unavailable or unauthorized"); }
}
