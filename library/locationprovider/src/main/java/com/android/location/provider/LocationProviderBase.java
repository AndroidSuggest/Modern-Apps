package com.android.location.provider;

import android.content.Context;
import android.location.Location;
import android.os.IBinder;
import android.os.WorkSource;

import java.util.List;

/**
 * Compile-only stub of the platform's unbundled network/fused location provider base. Only the
 * members our subclass uses/overrides are declared; the real class supplies the rest.
 * `onSetRequest` is the sole abstract method. Do not package into an APK.
 */
public abstract class LocationProviderBase {
    public LocationProviderBase(String tag, ProviderPropertiesUnbundled properties) {}

    public LocationProviderBase(Context context, String tag, ProviderPropertiesUnbundled properties) {}

    public IBinder getBinder() {
        throw new RuntimeException("stub");
    }

    public void setEnabled(boolean enabled) {}

    public void setProperties(ProviderPropertiesUnbundled properties) {}

    public void reportLocation(Location location) {}

    public void reportLocations(List<Location> locations) {}

    protected abstract void onSetRequest(ProviderRequestUnbundled request, WorkSource source);
}
