package metadata;

import java.nio.charset.StandardCharsets;

public class MetadataCommand {

    public enum Type {
        PUT,
        DELETE
    }

    private final Type type;
    private final String key;
    private final String value;

    private MetadataCommand(Type type, String key, String value) {
        this.type = type;
        this.key = key;
        this.value = value;
    }

    public static MetadataCommand put(String key, String value) {
        return new MetadataCommand(Type.PUT, key, value);
    }

    public static MetadataCommand delete(String key) {
        return new MetadataCommand(Type.DELETE, key, null);
    }

    public Type getType() {
        return type;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    // -------- Serialization --------

    public byte[] serialize() {
        String encoded;
        if (type == Type.PUT) {
            encoded = "PUT|" + key + "|" + value;
        } else {
            encoded = "DELETE|" + key;
        }
        return encoded.getBytes(StandardCharsets.UTF_8);
    }

    public static MetadataCommand deserialize(byte[] bytes) {
        String decoded = new String(bytes, StandardCharsets.UTF_8);
        String[] parts = decoded.split("\\|");

        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid MetadataCommand encoding: " + decoded);
        }

        Type type = Type.valueOf(parts[0]);

        if (type == Type.PUT) {
            if (parts.length != 3) {
                throw new IllegalArgumentException("Invalid PUT encoding: " + decoded);
            }
            return put(parts[1], parts[2]);
        } else if (type == Type.DELETE) {
            return delete(parts[1]);
        }

        throw new IllegalArgumentException("Unknown command type: " + decoded);
    }
}

