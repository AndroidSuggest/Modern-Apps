package android.app.backup;

/**
 * Compile-only stub of the framework's {@code @SystemApi}
 * {@code android.app.backup.RestoreSet}. Identifies an available backup set by its
 * token. Not packaged; the real class is provided by the framework at runtime.
 */
public class RestoreSet {
    public String name;
    public String device;
    public long token;

    public RestoreSet() {
    }

    public RestoreSet(String name, String device, long token) {
        this.name = name;
        this.device = device;
        this.token = token;
    }
}
