package io.github.qhung13.asep.codedelivery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Deterministic in-memory remote simulator. No HTTP/Git client, credentials, force-push or delete capability. */
public final class FakeGitLab {
    public record Branch(String repository, String name, String head, UUID owner, String intentHash) {}
    public record MergeRequest(long iid, String repository, String source, String target, String head,
            boolean draft, UUID owner, String intentHash) {}
    enum Reply { NORMAL, LOST, REJECTED }
    static final class Uncertain extends RuntimeException {}
    static final class Rejected extends RuntimeException {}
    private final Map<String, Branch> branches = new HashMap<>();
    private final List<MergeRequest> requests = new ArrayList<>();
    private final Map<String, String> targets = new HashMap<>();
    private int pushes, creates, pushCalls, createCalls;
    Reply pushReply = Reply.NORMAL, mrReply = Reply.NORMAL;
    Runnable beforePush = () -> {}, beforeMr = () -> {};

    public synchronized void enrollSyntheticTarget(String repository, String head) { targets.put(repository, head); }
    synchronized String target(String repository) { return targets.get(repository); }
    synchronized Branch branch(DeliveryIntent intent) { return branches.get(intent.repositoryId() + ":" + intent.sourceBranch()); }
    synchronized List<MergeRequest> mergeRequests(DeliveryIntent intent) {
        // Broad candidate search deliberately includes collisions with different owners/heads.
        return requests.stream().filter(m -> m.repository().equals(intent.repositoryId()) && m.source().equals(intent.sourceBranch())).toList();
    }
    synchronized Branch push(DeliveryIntent intent) {
        pushCalls++;
        beforePush.run();
        if (pushReply == Reply.REJECTED) throw new Rejected();
        if (branch(intent) != null) throw new Rejected(); // Never overwrite, even for same ownership.
        Branch result = new Branch(intent.repositoryId(), intent.sourceBranch(), intent.candidateCommitSha(), intent.operationId(), intent.bodyHash());
        branches.put(intent.repositoryId() + ":" + intent.sourceBranch(), result);
        pushes++;
        if (pushReply == Reply.LOST) throw new Uncertain();
        return result;
    }
    synchronized MergeRequest create(DeliveryIntent intent, ControlledDraft draft) {
        if (!draft.equals(ControlledDraft.render(intent))) throw new Rejected();
        createCalls++;
        beforeMr.run();
        if (mrReply == Reply.REJECTED) throw new Rejected();
        Branch source = branch(intent);
        if (source == null) throw new Rejected();
        MergeRequest result = new MergeRequest(requests.size() + 1L, intent.repositoryId(), intent.sourceBranch(),
                intent.targetBranch(), source.head(), true, intent.operationId(), intent.bodyHash());
        requests.add(result);
        creates++;
        if (mrReply == Reply.LOST) throw new Uncertain();
        return result;
    }
    // Test-only fault configuration is package-private; it adds no live capability.
    synchronized void seed(Branch branch) { branches.put(branch.repository() + ":" + branch.name(), branch); }
    synchronized void seed(MergeRequest mr) { requests.add(mr); }
    public synchronized int pushMutations() { return pushes; }
    public synchronized int mrMutations() { return creates; }
    public synchronized int pushCalls() { return pushCalls; }
    public synchronized int mrCalls() { return createCalls; }
}
