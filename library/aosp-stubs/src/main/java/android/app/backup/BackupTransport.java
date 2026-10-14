package android.app.backup;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

/**
 * Compile-only stub of the framework's {@code @SystemApi}
 * {@code android.app.backup.BackupTransport}. Mirrors the AOSP surface (method
 * signatures and constant values) that a backup transport implements. Not packaged;
 * the real class is provided by the framework at runtime on a system image.
 */
public class BackupTransport {
    // Result codes — values must match AOSP because they are inlined into callers.
    public static final int TRANSPORT_OK = 0;
    public static final int TRANSPORT_ERROR = 1;
    public static final int TRANSPORT_NOT_INITIALIZED = 2;
    public static final int TRANSPORT_PACKAGE_REJECTED = 3;
    public static final int AGENT_ERROR = 4;
    public static final int AGENT_UNKNOWN = 5;
    public static final int TRANSPORT_QUOTA_EXCEEDED = 6;
    public static final int TRANSPORT_NON_INCREMENTAL_BACKUP_REQUIRED = 7;

    // Sentinel returned by getNextFullRestoreDataChunk when a stream is exhausted.
    public static final int NO_MORE_DATA = -1;

    // performBackup / performFullBackup flags.
    public static final int FLAG_USER_INITIATED = 1;
    public static final int FLAG_NON_INCREMENTAL = 1 << 1;
    public static final int FLAG_INCREMENTAL = 1 << 2;
    public static final int FLAG_DATA_NOT_CHANGED = 1 << 3;

    public IBinder getBinder() {
        return null;
    }

    public String name() {
        return null;
    }

    public Intent configurationIntent() {
        return null;
    }

    public String currentDestinationString() {
        return null;
    }

    public Intent dataManagementIntent() {
        return null;
    }

    public CharSequence dataManagementIntentLabel() {
        return null;
    }

    public String transportDirName() {
        return null;
    }

    public long requestBackupTime() {
        return 0;
    }

    public int initializeDevice() {
        return TRANSPORT_ERROR;
    }

    public int performBackup(PackageInfo packageInfo, ParcelFileDescriptor inFd) {
        return TRANSPORT_ERROR;
    }

    public int performBackup(PackageInfo packageInfo, ParcelFileDescriptor inFd, int flags) {
        return performBackup(packageInfo, inFd);
    }

    public int clearBackupData(PackageInfo packageInfo) {
        return TRANSPORT_ERROR;
    }

    public int finishBackup() {
        return TRANSPORT_ERROR;
    }

    public RestoreSet[] getAvailableRestoreSets() {
        return null;
    }

    public long getCurrentRestoreSet() {
        return 0;
    }

    public int startRestore(long token, PackageInfo[] packages) {
        return TRANSPORT_ERROR;
    }

    public RestoreDescription nextRestorePackage() {
        return null;
    }

    public int getRestoreData(ParcelFileDescriptor outFd) {
        return TRANSPORT_ERROR;
    }

    public void finishRestore() {
    }

    public long requestFullBackupTime() {
        return 0;
    }

    public int checkFullBackupSize(long size) {
        return TRANSPORT_OK;
    }

    public int performFullBackup(PackageInfo targetPackage, ParcelFileDescriptor socket) {
        return TRANSPORT_ERROR;
    }

    public int performFullBackup(PackageInfo targetPackage, ParcelFileDescriptor socket, int flags) {
        return performFullBackup(targetPackage, socket);
    }

    public int sendBackupData(int numBytes) {
        return TRANSPORT_ERROR;
    }

    public void cancelFullBackup() {
    }

    public int getTransportFlags() {
        return 0;
    }

    public long getBackupQuota(String packageName, boolean isFullBackup) {
        return Long.MAX_VALUE;
    }

    public int getNextFullRestoreDataChunk(ParcelFileDescriptor socket) {
        return NO_MORE_DATA;
    }

    public int abortFullRestore() {
        return TRANSPORT_OK;
    }
}
