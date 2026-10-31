package io.github.qhung13.asep.codedelivery;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import static io.github.qhung13.asep.codedelivery.TestProjectDeliveryGate.*;

/** Separately provisioned, root-owned operational evidence. Request references,
 * HTTP 403, UI omissions and a feature flag cannot establish any of these facts.
 * Human evidence collection remains an external prerequisite; this reader does
 * not issue attestations or elevate a Developer credential to inspect settings.
 */
final class ProtectedDeliveryEnrollment {
    static final Set<String> REQUIRED = Set.of("project-namespace-permissions","inherited-group-compliance",
        "project-group-instance-runners","runner-execution-protection","variable-metadata-all-levels",
        "candidate-ci-includes","pipeline-security-policies","auto-devops","protected-branches",
        "environments-deployment-capabilities","hooks-integrations-deploy-keys-tokens","job-token-capabilities",
        "source-push-ci-behavior","draft-mr-ci-behavior","isolated-controller-verifier",
        "isolated-operator-executor","credential-lifecycle-revocation-plan","controlled-fault-window");
    record Fact(String status,String evidenceSha256,String provenance) {}
    record ReadOnlyObservation(String subject,String collector,String method,int httpStatus,boolean complete,Instant startedAt,Instant completedAt,String assessment,String requestHash,Integer itemCount) {}
    record Permit(String requestHash,long projectId,String repositoryPath,String sourceBranch,String targetBranch,
        String safeCiProfileSha256,String readCredentialSha256,String writeCredentialSha256,
        int writeAccessLevel,Set<String> writeScopes,Instant credentialExpiresAt,
        Instant inspectionStartedAt,Instant inspectionCompletedAt,Instant expiresAt,Map<String,Fact> facts) {}
    @FunctionalInterface interface Records { byte[] read(String relative,int bound); }
    private final Records records;private final Clock clock;
    ProtectedDeliveryEnrollment(Path root,Clock clock){this((relative,bound)->{
        ProtectedOperatorFiles.hierarchy(root,0);
        return ProtectedOperatorFiles.read(root,root.resolve(relative),0,bound,false);
    },clock);}
    // Explicit trusted capability seam for owned fixtures; production always
    // uses the root-anchored constructor above, never request-selected records.
    ProtectedDeliveryEnrollment(Records records,Clock clock){this.records=java.util.Objects.requireNonNull(records);this.clock=clock;}
    Permit require(TestProjectDeliveryRequest request){
        var permit=JdbcDeliveryLedger.JSON.readValue(records.read(request.operationId()+".json",16384),Permit.class);
        validate(permit,request,clock.instant());
        for(var fact:permit.facts().entrySet()){
            byte[] bytes=records.read("evidence/"+fact.getValue().evidenceSha256()+".json",65536);
            if(!ControllerEvidenceStore.digest(bytes).equals(fact.getValue().evidenceSha256()))throw denied();
            var observation=JdbcDeliveryLedger.JSON.readValue(bytes,ReadOnlyObservation.class);
            validateObservation(observation,fact.getKey(),fact.getValue(),permit);
        }
        validate(permit,request,clock.instant());return permit;
    }
    static void validate(Permit p,TestProjectDeliveryRequest r,Instant now){
        if(p==null||!r.bodyHash().equals(p.requestHash())||p.projectId()!=87786140||!"qhung-13/asep-m01-sandbox".equals(p.repositoryPath())
            ||!r.sourceBranch().equals(p.sourceBranch())||!"main".equals(p.targetBranch())||!r.safeCiProfileSha256().equals(p.safeCiProfileSha256())
            ||p.readCredentialSha256()==null||!p.readCredentialSha256().matches("[0-9a-f]{64}")||p.writeCredentialSha256()==null||!p.writeCredentialSha256().matches("[0-9a-f]{64}")
            ||p.readCredentialSha256().equals(p.writeCredentialSha256())||p.writeAccessLevel()!=30||!Set.of("api","write_repository").equals(p.writeScopes())
            ||p.credentialExpiresAt()==null||!p.credentialExpiresAt().isAfter(now)||p.inspectionStartedAt()==null||p.inspectionCompletedAt()==null
            ||p.inspectionStartedAt().isAfter(p.inspectionCompletedAt())||p.inspectionCompletedAt().isAfter(now)
            ||Duration.between(p.inspectionStartedAt(),now).compareTo(Duration.ofMinutes(5))>0||p.expiresAt()==null||!p.expiresAt().isAfter(now)
            ||p.expiresAt().isAfter(p.inspectionStartedAt().plus(Duration.ofMinutes(5)))||p.facts()==null||!p.facts().keySet().equals(REQUIRED))throw denied();
        for(var fact:p.facts().values())if(fact==null||!"VERIFIED".equals(fact.status())||fact.evidenceSha256()==null||!fact.evidenceSha256().matches("[0-9a-f]{64}")||fact.provenance()==null||!fact.provenance().matches("[A-Za-z0-9][A-Za-z0-9 ./_:=-]{0,255}"))throw denied();
    }
    int inventoryCount(Permit p,String subject){
        Fact fact=p.facts().get(subject);
        byte[] bytes=records.read("evidence/"+fact.evidenceSha256()+".json",65536);
        if(!ControllerEvidenceStore.digest(bytes).equals(fact.evidenceSha256()))throw denied();
        var observation=JdbcDeliveryLedger.JSON.readValue(bytes,ReadOnlyObservation.class);validateObservation(observation,subject,fact,p);
        if(observation.itemCount()==null||observation.itemCount()<0||observation.itemCount()>100000)throw denied();return observation.itemCount();
    }
    static void validateObservation(ReadOnlyObservation o,String subject,Fact fact,Permit p){
        if(o==null||!subject.equals(o.subject())||!fact.provenance().equals(o.collector())||!o.complete()||!"SAFE_FOR_EXACT_PROBE".equals(o.assessment())||!p.requestHash().equals(o.requestHash())
            ||!("GET".equals(o.method())&&o.httpStatus()==200||"OWNER_UI".equals(o.method())&&o.httpStatus()==0)
            ||o.startedAt()==null||o.completedAt()==null||o.startedAt().isBefore(p.inspectionStartedAt())||o.startedAt().isAfter(o.completedAt())||o.completedAt().isAfter(p.inspectionCompletedAt()))throw denied();
    }
    static Set<Requirement> established(){return Set.of(Requirement.EXACT_TEST_PROJECT_ENROLLMENT,Requirement.ACCOUNT_AND_CREDENTIAL_REVIEW,
        Requirement.CREDENTIAL_ISOLATION_AND_LIFECYCLE,Requirement.SAFE_CI_AND_INHERITED_SETTINGS,Requirement.CONTROLLED_MR_AND_FAULT_WINDOWS,Requirement.TRUSTED_LIVE_EXECUTOR_WIRING);}
    private static IllegalStateException denied(){return new IllegalStateException("Protected live enrollment unavailable or incomplete");}
}
