(Raft-based, Single-Developer Scale)
A minimal, production-style implementation of a Raft-backed metadata and coordination service.

This project models how a FAANG-grade coordination system (e.g., etcd / ZooKeeper) would be architected — but scoped intentionally so that a single engineer can fully implement, reason about, and defend it in interviews.

The focus is on:

Correctness

Clarity

Observability

Clean separation between consensus and state machine

Not on performance or production completeness.

🌐 Live Demo
https://consensus-backed-metadata-coordination.onrender.com

🧠 System Architecture
1️⃣ Consensus Layer (Raft Core)
Implements core Raft protocol components:

Leader election

Randomized election timeouts

RequestVote RPC

AppendEntries (heartbeats + log replication)

Log consistency enforcement

Commit index tracking

State machine application

The Raft logic is transport-agnostic and cleanly separated from the metadata layer.

🏛 Raft Components
Roles & Terms
RaftNode represents a single server.

RaftRole: FOLLOWER, CANDIDATE, LEADER.

LeaderElection manages:

currentTerm

votedFor

Role transitions

Majority vote counting

Election scheduling

Leader Election
Followers start with randomized election timeouts.

On timeout:

Increment term

Vote for self

Send RequestVote RPCs

Majority vote → become leader.

Higher term observed → step down to follower.

Implements:

Monotonic term rule

One vote per term per node

Log up-to-date rule

Heartbeats
Leaders periodically send empty AppendEntries:

Reset follower election timers

Maintain leadership

Prevent unnecessary elections

Log Replication
RaftLog:

1-based indexing

Index 0 sentinel (term 0)

RaftLogEntry(term, commandBytes)

Follower behavior:

Reject if prevLogIndex / prevLogTerm mismatch

Truncate conflicting suffix

Append new entries

Leader behavior (simplified for demo):

Append locally

Treat entry as committed immediately

Replicate full log to followers

This is intentionally inefficient but highly understandable.

🗄 Metadata State Machine
Command Model
Two replicated operations:

PUT(key, value)

DELETE(key)

Encoded as self-describing UTF-8 payloads:

PUT\n<key>\n<value>
DEL\n<key>
Metadata Store
In-memory hierarchical key-value store

Backed by TreeMap<String,String>

Supports:

Apply replicated commands

Leader-only linearizable GET

Snapshot view for observability

Raft ↔ State Machine Wiring
All writes go through the Raft log.

Committed entries are applied sequentially via LogApplier.

Reads are leader-only to preserve linearizability semantics.

This ensures strict separation between consensus and business logic.

🧪 Demo Harness
An in-JVM 3-node cluster using an in-process RPC client.

Demonstrates:

Initial leader election

Metadata replication

Log convergence across nodes

Heartbeat stability

Leader failure simulation

Re-election in higher term

Continued replication after failover

Final metadata consistency

The demo proves:

Safety under re-election

Log convergence

State machine consistency

Deterministic behavior

🚫 Intentionally Not Implemented
To maintain clarity and interview defensibility:

No disk persistence / WAL

No snapshots or log compaction

No RocksDB

No gRPC transport

No matchIndex / nextIndex tracking

No quorum-based commit tracking

No multi-process deployment

Nodes exist within a single JVM.

These omissions are deliberate.

The project focuses on a correct and understandable Raft core — not a full etcd replacement.

🎯 Why This Project Is Interesting
This implementation demonstrates:

Deep understanding of consensus algorithms

Correct handling of Raft safety rules

Clean separation of consensus and state machine

Deterministic replication logic

Failure handling and re-election correctness

Production-style structuring

Observability-first design

It is designed to be technically defensible in senior-level distributed systems interviews.

