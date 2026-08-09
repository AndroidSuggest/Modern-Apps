package com.android.location.provider;

import android.location.Address;
import android.location.GeocoderParams;
import android.os.IBinder;

import java.util.List;

/**
 * Compile-only stub of the platform's unbundled geocode provider base. Both onGet* methods are
 * abstract; return null on success (results go into {@code addrs}) or an error string on failure.
 * Do not package into an APK.
 */
public abstract class GeocodeProvider {
    public abstract String onGetFromLocation(
            double latitude, double longitude, int maxResults,
            GeocoderParams params, List<Address> addrs);

    public abstract String onGetFromLocationName(
            String locationName,
            double lowerLeftLatitude, double lowerLeftLongitude,
            double upperRightLatitude, double upperRightLongitude,
            int maxResults, GeocoderParams params, List<Address> addrs);

    public IBinder getBinder() {
        throw new RuntimeException("stub");
    }
}
