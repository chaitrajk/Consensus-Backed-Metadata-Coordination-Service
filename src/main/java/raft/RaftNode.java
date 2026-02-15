package raft;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.nio.charset.StandardCharsets;

import metadata.MetadataCommand;
import metadata.MetadataStore;

/**
 * Core Raft node wrapper.
 *
 * For this first increment we focus solely on correct leader election
 * semantics; log replication and the metadata state machine will be
 * layered on top in subsequent steps.
 *
 * This class owns:
 * - Node identity and peer list
 * - A single-threaded scheduler for time-related events
 * - A LeaderElection instance encapsulating the Raft election rules
 *
 * Design constraints:
 * - All mutable Raft state is confined to the election component or
 *   will later be guarded by RaftNode's own lock.
 * - Networking is abstracted via LeaderElection.RpcClient so that
 *   the same logic works for in-process tests and gRPC deployments.
 */
public class RaftNode implements AutoCloseable {

    private final String nodeId;
    private final List<String> peerIds;
    private final ScheduledExecutorService scheduler;
    private final LeaderElection leaderElection;
    private final RaftLog log;
    private final MetadataStore metadataStore;

    public RaftNode(String nodeId,
                    List<String> peerIds,
                    LeaderElection.RpcClient rpcClient,
                    Duration minElectionTimeout,
                    Duration maxElectionTimeout) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.peerIds = Objects.requireNonNull(peerIds, "peerIds");
        this.log = new RaftLog();
        this.metadataStore = new MetadataStore();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "raft-node-" + nodeId);
            t.setDaemon(true);
            return t;
        });

        this.leaderElection = new LeaderElection(
                nodeId,
                peerIds,
                rpcClient,
                log,
                (index, entry) -> applyMetadataEntry(index, entry),
                scheduler,
                minElectionTimeout,
                maxElectionTimeout
        );
    }

    /**
     * Start this Raft node as a follower.
     */
    public void start() {
        leaderElection.start();
    }

    /**
     * Cleanly stop background tasks associated with this node.
     */
    @Override
    public void close() {
        leaderElection.stop();
        scheduler.shutdownNow();
    }

    // ------------ Raft RPC surfaces (to be wired to gRPC later) ------------

    public LeaderElection.RequestVoteResponse handleRequestVote(
            LeaderElection.RequestVoteRequest request,
            int lastLogIdx,
            int lastLogTerm) {
        return leaderElection.handleRequestVote(request, lastLogIdx, lastLogTerm);
    }

    public void onHeartbeatFromLeader(int leaderTerm) {
        leaderElection.onHeartbeatFromLeader(leaderTerm);
    }

    public LeaderElection.AppendEntriesResponse handleAppendEntries(
            LeaderElection.AppendEntriesRequest request) {
        return leaderElection.handleAppendEntries(request);
    }

    public int appendDemoEntryAndReplicate(byte[] command) {
        return leaderElection.appendEntryAndReplicate(command);
    }

    // ------------ Simplified in-process RpcClient for early testing ------------

    public static class InProcessRpcClient implements LeaderElection.RpcClient {

        private final Map<String, RaftNode> cluster;

        public InProcessRpcClient(Map<String, RaftNode> cluster) {
            this.cluster = Objects.requireNonNull(cluster, "cluster");
        }

        @Override
        public CompletableFuture<LeaderElection.RequestVoteResponse> requestVote(
                String peerId,
                LeaderElection.RequestVoteRequest request) {
            RaftNode peer = cluster.get(peerId);
            if (peer == null) {
                CompletableFuture<LeaderElection.RequestVoteResponse> failed = new CompletableFuture<>();
                failed.completeExceptionally(new IllegalArgumentException("Unknown peer: " + peerId));
                return failed;
            }
            return CompletableFuture.supplyAsync(() -> {
                int lastIdx = peer.getLastLogIndex();
                int lastTerm = peer.getLastLogTerm();
                return peer.handleRequestVote(request, lastIdx, lastTerm);
            });
        }

        @Override
        public CompletableFuture<LeaderElection.AppendEntriesResponse> appendEntries(
                String peerId,
                LeaderElection.AppendEntriesRequest request) {
            RaftNode peer = cluster.get(peerId);
            if (peer == null) {
                CompletableFuture<LeaderElection.AppendEntriesResponse> failed = new CompletableFuture<>();
                failed.completeExceptionally(new IllegalArgumentException("Unknown peer: " + peerId));
                return failed;
            }
            return CompletableFuture.supplyAsync(() -> peer.handleAppendEntries(request));
        }
    }

    // ------------ Introspection helpers (useful for tests / demo) ------------

    public String getNodeId() {
        return nodeId;
    }

    public List<String> getPeerIds() {
        return peerIds;
    }

    public RaftRole getRole() {
        return leaderElection.getRole();
    }

    public int getCurrentTerm() {
        return leaderElection.getCurrentTerm();
    }

    public int getLastLogIndex() {
        return log.getLastIndex();
    }

    public int getLastLogTerm() {
        return log.getLastTerm();
    }

    public String dumpLog() {
        StringBuilder sb = new StringBuilder();
        var entries = log.getAllEntriesSnapshot();
        for (int idx = 0; idx < entries.size(); idx++) {
            RaftLog.RaftLogEntry e = entries.get(idx);
            String cmd = e.command == null ? "null" : new String(e.command, StandardCharsets.UTF_8);
            sb.append("    [index=").append(idx)
                    .append(", term=").append(e.term)
                    .append(", cmd=").append(cmd)
                    .append("]\n");
        }
        return sb.toString();
    }

    // ------------ Metadata state machine API ------------

    public void putMetadata(String key, String value) {
        ensureLeaderForClientRequest("PUT");
        MetadataCommand cmd = MetadataCommand.put(key, value);
        leaderElection.appendEntryAndReplicate(cmd.serialize());
    }

    public void deleteMetadata(String key) {
        ensureLeaderForClientRequest("DELETE");
        MetadataCommand cmd = MetadataCommand.delete(key);
        leaderElection.appendEntryAndReplicate(cmd.serialize());
    }

    public String getMetadata(String key) {
        ensureLeaderForClientRequest("GET");
        return metadataStore.get(key);
    }

    public Map<String, String> dumpMetadataSnapshot() {
        return metadataStore.snapshot();
    }

    private void ensureLeaderForClientRequest(String op) {
        if (getRole() != RaftRole.LEADER) {
            throw new IllegalStateException(op + " is only allowed on the leader. Node "
                    + nodeId + " is " + getRole());
        }
    }

    private void applyMetadataEntry(int index, RaftLog.RaftLogEntry entry) {
        MetadataCommand cmd = MetadataCommand.deserialize(entry.command);
        metadataStore.apply(cmd);
    }

    /**
     * Return a snapshot of all log entries for external inspection.
     */
    public synchronized List<RaftLog.RaftLogEntry> getLogEntriesSnapshot() {
        return new ArrayList<>(log.getAllEntriesSnapshot());
    }
}


