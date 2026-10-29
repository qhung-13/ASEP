package io.github.qhung13.asep.codedelivery;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Clock;
import java.util.Arrays;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Explicit human-only installed CLI, not a Spring endpoint, autonomous worker or agent tool.
 * Default execution from the repository/agent UID is denied before issuer credential access.
 */
public final class OwnerApprovalCommand {
    private OwnerApprovalCommand() {}
    public static void main(String[] args) {
        try {
            if (args.length!=3 || !(args[0].equals("approve") || args[0].equals("revoke"))) throw new IllegalArgumentException();
            var console=System.console();
            if (console==null) throw new IllegalStateException();
            var policy=RestrictedOperatorRuntime.authenticate();
            boolean revoke=args[0].equals("revoke");
            int validity=Integer.parseInt(args[2]);
            if (validity<1 || validity>3600) throw new IllegalArgumentException();
            Path input=Path.of(args[1]).toAbsolutePath().normalize();
            // The human imports untrusted request bytes into an operator-owned review inbox.
            // Protected ancestors prevent the agent swapping a file for a FIFO during review.
            ProtectedOperatorFiles.hierarchy(input.getParent(),policy.operatorUid());
            byte[] body=ProtectedOperatorFiles.read(input.getParent(),input,policy.operatorUid(),8192,true);
            var request=JdbcDeliveryLedger.JSON.readValue(body,TestProjectDeliveryRequest.class);
            var evidence=new ProtectedDeliveryEvidence(policy.evidenceRoot(),policy.evidenceUid());
            var preparation=new TestProjectDeliveryPreparation(evidence,evidence,policy.profile(),Clock.systemUTC());
            // Revocation remains available after receipt expiry or verifier outage; it cannot create authority.
            if (!revoke) preparation.prepare(request);
            console.printf("%s%n",review(request,validity,revoke));
            String challenge=(revoke?"REVOKE ":"APPROVE ")+request.bodyHash();
            String answer=console.readLine("Type %s to confirm: ",challenge);
            if (!challenge.equals(answer)) throw new IllegalStateException();
            if (!revoke) preparation.prepare(request); // Rehash/revalidate after the human review window.
            byte[] secret=ProtectedOperatorFiles.read(policy.passwordFile().getParent(),policy.passwordFile(),policy.operatorUid(),4096,true);
            try {
                String password=new String(secret,StandardCharsets.US_ASCII);
                if(password.endsWith("\n")) password=password.substring(0,password.length()-1);
                if(password.isEmpty() || password.chars().anyMatch(c->c<33 || c>126)) throw new IllegalStateException();
                var authority=new OwnerGrantAuthority(new OperatorDataSource(policy,password),policy.databaseLogin());
                var receipt=revoke?authority.revoke(request):authority.approve(request,validity);
                console.printf("Grant %s; issued by database principal %s; expires %s; revoked %s%n",
                        receipt.grantId(),receipt.actor(),receipt.expiresAt(),receipt.revokedAt()!=null);
            } finally { Arrays.fill(secret,(byte)0); }
        } catch (Exception denied) {
            System.err.println("Owner approval unavailable/denied; inspect the exact grant audit before retrying an uncertain database outcome.");
            System.exit(1);
        }
    }
    static String review(TestProjectDeliveryRequest request,int seconds,boolean revoke) {
        return (revoke?"REVOKE":"APPROVE")+" exact sandbox Owner grant only\nProject: "+request.projectId()+" qhung-13/asep-m01-sandbox"
                +"\nOperation: "+request.operationId()+"\nRun/attempt/version: "+request.runId()+" / "+request.attemptId()+" / "+request.runVersion()
                +"\nSource: "+request.sourceBranch()+"\nTarget: main (no direct push, merge or approval)\nBase/expected target: "+request.baseSha()
                +"\nCandidate commit/tree: "+request.candidateCommitSha()+" / "+request.candidateTreeSha()+"\nManifest: "+request.manifestSha256()
                +"\nSeal/verification receipts: "+request.sealReceiptId()+" / "+request.verificationReceiptId()+"\nVerifier profile: "+request.verifierProfileSha256()
                +"\nPolicy/epoch: "+request.policyId()+" / "+request.policyEpoch()+"\nSafe-CI profile reference (not proof): "+request.safeCiProfileSha256()
                +"\nValidity seconds: "+seconds+"\nCanonical request hash: "+request.bodyHash()+"\nOnly changed file: "+request.probePath()
                +"\nExact probe content:\n"+new String(TestProjectDeliveryPreparation.probeContent(request.operationId()),StandardCharsets.US_ASCII)
                +"Workflow, safe-CI, credentials and every delivery gate remain independently mandatory.";
    }
    private static final class OperatorDataSource implements DataSource {
        private final RestrictedOperatorRuntime.Policy policy; private final String password;
        OperatorDataSource(RestrictedOperatorRuntime.Policy policy,String password) { this.policy=policy;this.password=password; }
        @Override public Connection getConnection() throws SQLException {
            var properties=new Properties(); properties.setProperty("user",policy.databaseLogin());properties.setProperty("password",password);
            properties.setProperty("sslmode","verify-full");properties.setProperty("sslrootcert",policy.databaseCa().toString());
            properties.setProperty("connectTimeout","3");properties.setProperty("socketTimeout","5");
            properties.setProperty("options","-c statement_timeout=4000 -c lock_timeout=3000");
            return DriverManager.getConnection(policy.databaseUrl(),properties);
        }
        @Override public Connection getConnection(String user,String password) throws SQLException { throw new SQLFeatureNotSupportedException(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter value) throws SQLException { throw new SQLFeatureNotSupportedException(); }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 3; }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { throw new SQLFeatureNotSupportedException(); }
        @Override public <T>T unwrap(Class<T> type) throws SQLException { throw new SQLFeatureNotSupportedException(); }
        @Override public boolean isWrapperFor(Class<?> type) { return false; }
    }
}
