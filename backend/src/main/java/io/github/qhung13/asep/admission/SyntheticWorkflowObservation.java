package io.github.qhung13.asep.admission;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import static io.github.qhung13.asep.admission.SyntheticManualTicketAdmission.*;

/** Minimal authorized synthetic Application projection, not a public endpoint or
 * a new Viewer grant. Its port reads each owning component; no cross-schema SQL.
 * Caller identity comes from the injected authenticated context, never a request
 * role field. Only sanitized IDs/counters/evidence references are projected. */
final class SyntheticWorkflowObservation {
    record Workflow(String runId,String subjectKey,String policyId,long policyEpoch,String sourceFingerprint,
            String state,long runVersion,int budgetLimit,int budgetUsed,int modelCallsReserved,int modelTokensReserved,int remainingSeconds,int corrections,
            List<String> evidenceRefs) {}
    record Delivery(String branchPush,String draftMr,String ci,String humanMerge,boolean synthetic) {}
    record View(Workflow workflow,Delivery delivery) {}
    interface Port { View read(String runId,String subjectKey); }
    private final SecurityContext security;private final PolicyProvider policies;private final Port port;
    SyntheticWorkflowObservation(SecurityContext security,PolicyProvider policies,Port port){
        this.security=Objects.requireNonNull(security);this.policies=Objects.requireNonNull(policies);this.port=Objects.requireNonNull(port);
    }
    View read(Prepared original,String runId){
        try{
            UUID.fromString(runId);Actor actor=security.authenticatedActor();Policy p=policies.current();
            if(original==null||actor==null||p==null||!p.enabled()||actor.role()!=Role.OWNER||!actor.id().equals(p.actorId()))throw new Denied();
            var c=original.command();var repo=p.repository();
            if(repo==null||!c.subjectKey().startsWith(p.fixturePrefix()+":")||!c.actorId().equals(actor.id())
                    ||!c.policyId().equals(p.id())||c.policyEpoch()!=p.epoch()||!c.jiraSiteId().equals(p.siteId())
                    ||!c.jiraProjectId().equals(p.projectId())||!c.gitLabProjectId().equals(repo.projectId())
                    ||!c.repositoryBaseSha().equals(repo.baseSha())||!c.targetBranch().equals(repo.targetBranch())
                    ||!c.observedTargetHeadSha().equals(repo.observedHeadSha()))throw new Denied();
            View value=port.read(runId,c.subjectKey());var w=value.workflow();var d=value.delivery();
            if(!w.runId().equals(runId)||!w.subjectKey().equals(c.subjectKey())||!w.policyId().equals(c.policyId())
                    ||w.policyEpoch()!=c.policyEpoch()||!w.sourceFingerprint().equals(c.sourceFingerprintSha256())
                    ||w.runVersion()<1||w.budgetUsed()<0||w.budgetUsed()>w.budgetLimit()||w.modelCallsReserved()<0||w.modelTokensReserved()<0
                    ||w.remainingSeconds()<0||w.remainingSeconds()>300||w.corrections()<0
                    ||w.evidenceRefs()==null||w.evidenceRefs().size()>32||w.evidenceRefs().stream().anyMatch(e->e==null||!e.startsWith("synthetic:")||e.length()>256)
                    ||!Set.of("PLANNED","IN_PROGRESS","CONFIRMED","UNKNOWN","BLOCKED","MANUAL_RECONCILIATION_REQUIRED").contains(d.branchPush())
                    ||!Set.of("PLANNED","IN_PROGRESS","CONFIRMED","UNKNOWN","BLOCKED","MANUAL_RECONCILIATION_REQUIRED").contains(d.draftMr())
                    ||!Set.of("NOT_OBSERVED","NO_PIPELINE","PENDING","FAILED","PASSED").contains(d.ci())
                    ||!Set.of("NOT_MERGED","MERGED","NOT_OBSERVED").contains(d.humanMerge())||!d.synthetic())throw new Denied();
            return value;
        }catch(RuntimeException denied){throw new Denied();}
    }
}
