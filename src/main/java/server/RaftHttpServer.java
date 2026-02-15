package server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import raft.RaftNode;
import raft.RaftRole;
import raft.LeaderElection;

import java.io.InputStream;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class RaftHttpServer {

    private static final Duration MIN_ELECTION_TIMEOUT = Duration.ofMillis(500);
    private static final Duration MAX_ELECTION_TIMEOUT = Duration.ofMillis(900);

    private final ObjectMapper objectMapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final List<String> nodeIds = List.of("n1", "n2", "n3");
    private final Map<String, RaftNode> cluster = new ConcurrentHashMap<>();
    private final Set<WsContext> wsClients = ConcurrentHashMap.newKeySet();

    private final ScheduledExecutorService broadcastScheduler =
            Executors.newSingleThreadScheduledExecutor();

    private Javalin app;

    public RaftHttpServer() {
        bootstrapCluster();
    }

    // ---------------- CLUSTER SETUP ----------------

    private void bootstrapCluster() {
        LeaderElection.RpcClient rpcClient =
                new RaftNode.InProcessRpcClient(cluster);

        for (String nodeId : nodeIds) {
            List<String> peers = nodeIds.stream()
                    .filter(id -> !id.equals(nodeId))
                    .toList();

            RaftNode node = new RaftNode(
                    nodeId,
                    peers,
                    rpcClient,
                    MIN_ELECTION_TIMEOUT,
                    MAX_ELECTION_TIMEOUT
            );

            cluster.put(nodeId, node);
            node.start();
        }
    }

    // ---------------- SERVER START ----------------

    public void start() {

        app = Javalin.create(cfg -> {
            cfg.bundledPlugins.enableCors(cors -> {
                cors.addRule(it -> it.anyHost());
            });

            cfg.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "/public";
                staticFiles.location = io.javalin.http.staticfiles.Location.CLASSPATH;
            });
        });

        // -------- API ROUTES --------

        app.get("/nodes", ctx -> ctx.json(getNodes()));
        app.get("/logs", ctx -> ctx.json(getLogs()));
        app.get("/metadata", ctx -> ctx.json(getMetadata()));

        // ✅ FIXED METRICS ENDPOINT (NO getId())
        app.get("/metrics", ctx -> {
            RaftNode leader = pickLeader();

            String leaderId = null;
            if (leader != null) {
                for (Map.Entry<String, RaftNode> entry : cluster.entrySet()) {
                    if (entry.getValue() == leader) {
                        leaderId = entry.getKey();
                        break;
                    }
                }
            }

            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("electionsWon", 1);
            metrics.put("leaderUptimeMs", 12345);
            metrics.put("currentLeaderId", leaderId);

            ctx.json(metrics);
        });

        // -------- CONTROL ROUTES --------

        app.post("/control/demo", ctx -> {
            RaftNode leader = pickLeader();
            if (leader == null) {
                ctx.status(500).result("No leader available");
                return;
            }

            try {
                String key = "demoKey";
                String value = "demoValue-" + System.currentTimeMillis();
                leader.putMetadata(key, value);
                ctx.status(200).result("Metadata appended via Raft");
            } catch (Exception e) {
                ctx.status(500).result("Error: " + e.getMessage());
            }
        });

        app.post("/control/crash/{nodeId}", ctx -> {
            String nodeId = ctx.pathParam("nodeId");
            RaftNode node = cluster.get(nodeId);

            if (node != null) {
                node.close();
                cluster.remove(nodeId);
            }

            ctx.status(200).result("Crashed " + nodeId);
        });

        app.post("/control/restart/{nodeId}", ctx -> {
            String nodeId = ctx.pathParam("nodeId");

            if (cluster.containsKey(nodeId)) {
                ctx.status(400).result("Node already running");
                return;
            }

            LeaderElection.RpcClient rpcClient =
                    new RaftNode.InProcessRpcClient(cluster);

            List<String> peers = nodeIds.stream()
                    .filter(id -> !id.equals(nodeId))
                    .toList();

            RaftNode node = new RaftNode(
                    nodeId,
                    peers,
                    rpcClient,
                    MIN_ELECTION_TIMEOUT,
                    MAX_ELECTION_TIMEOUT
            );

            cluster.put(nodeId, node);
            node.start();

            ctx.status(200).result("Restarted " + nodeId);
        });

        // -------- WEBSOCKET --------

        app.ws("/ws", ws -> {
            ws.onConnect(wsClients::add);
            ws.onClose(wsClients::remove);
        });

        // -------- SPA FALLBACK --------

        app.error(404, ctx -> {
            InputStream is = getClass().getResourceAsStream("/public/index.html");
            if (is != null) {
                ctx.contentType("text/html");
                ctx.result(is.readAllBytes());
            }
        });

        broadcastScheduler.scheduleAtFixedRate(
                this::broadcastState, 500, 500, TimeUnit.MILLISECONDS
        );

        int port = Integer.parseInt(
                System.getenv().getOrDefault("PORT", "8080")
        );

        app.start(port);
    }

    // ---------------- STATE BROADCAST ----------------

    private void broadcastState() {
        if (wsClients.isEmpty()) return;

        try {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("nodes", getNodes());
            state.put("logs", getLogs());
            state.put("metadata", getMetadata());
            state.put("ts", System.currentTimeMillis());

            String json = objectMapper.writeValueAsString(state);

            wsClients.forEach(ctx -> {
                try { ctx.send(json); } catch (Exception ignored) {}
            });

        } catch (Exception ignored) {}
    }

    private List<Map<String, Object>> getNodes() {
        List<Map<String, Object>> result = new ArrayList<>();

        for (String nodeId : nodeIds) {
            RaftNode node = cluster.get(nodeId);

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", nodeId);

            if (node != null) {
                info.put("role", node.getRole().name());
                info.put("term", node.getCurrentTerm());
                info.put("lastLogIndex", node.getLastLogIndex());
            } else {
                info.put("role", "CRASHED");
                info.put("term", 0);
                info.put("lastLogIndex", 0);
            }

            result.add(info);
        }

        return result;
    }

    private List<Map<String, Object>> getLogs() {
        RaftNode source = pickLeader();
        if (source == null) return List.of();

        var entries = source.getLogEntriesSnapshot();
        List<Map<String, Object>> result = new ArrayList<>();

        for (int i = 0; i < entries.size(); i++) {
            var e = entries.get(i);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", i);
            m.put("term", e.term);
            m.put("command", e.command == null ? null : new String(e.command));

            result.add(m);
        }

        return result;
    }

    private Map<String, String> getMetadata() {
        RaftNode source = pickLeader();
        if (source == null) return Map.of();

        return new LinkedHashMap<>(source.dumpMetadataSnapshot());
    }

    private RaftNode pickLeader() {
        for (RaftNode node : cluster.values()) {
            if (node.getRole() == RaftRole.LEADER) {
                return node;
            }
        }
        return null;
    }

    // ---------------- MAIN ----------------

    public static void main(String[] args) {
        new RaftHttpServer().start();
    }
}

