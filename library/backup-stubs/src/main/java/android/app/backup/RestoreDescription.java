package android.app.backup;

/**
 * Compile-only stub of the framework's {@code @SystemApi}
 * {@code android.app.backup.RestoreDescription}. Describes the next package to
 * restore and whether its data is key/value or a full stream. Not packaged; the real
 * class is provided by the framework at runtime.
 */
public class RestoreDescription {
    public static final int TYPE_KEY_VALUE = 1;
    public static final int TYPE_FULL_STREAM = 2;

    /** Sentinel returned by {@code nextRestorePackage()} when the set is exhausted. */
    public static final RestoreDescription NO_MORE_PACKAGES =
            new RestoreDescription("NO_MORE_PACKAGES", 0);

    private final String mPackageName;
    private final int mDataType;

    public RestoreDescription(String packageName, int dataType) {
        mPackageName = packageName;
        mDataType = dataType;
    }

    public String getPackageName() {
        return mPackageName;
    }

    public int getDataType() {
        return mDataType;
    }
}
