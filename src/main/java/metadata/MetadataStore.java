package metadata;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class MetadataStore {

    private final Map<String, String> store = new HashMap<>();

    // Apply a committed MetadataCommand
    public synchronized void apply(MetadataCommand cmd) {
        switch (cmd.getType()) {
            case PUT -> store.put(cmd.getKey(), cmd.getValue());
            case DELETE -> store.remove(cmd.getKey());
        }
    }

    // Read-only access
    public synchronized String get(String key) {
        return store.get(key);
    }

    // Snapshot for debugging / HTTP endpoint
    public synchronized Map<String, String> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(store));
    }
}

