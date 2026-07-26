package com.vayunmathur.library.map

/**
 * Pure-Kotlin geo point replacing `org.maplibre.spatialk.geojson.Position`.
 * longitude first, latitude second (same order as GeoJSON).
 *
 * This type is used by :library:map and its non-maplibre consumers
 * (findfamily, weather, photos) so they no longer need a transitive
 * dependency on `org.maplibre.spatialk:geojson`.
 */
data class GeoPoint(
    val longitude: Double,
    val latitude: Double,
)

/**
 * Pure-Kotlin bounding box replacing `org.maplibre.spatialk.geojson.BoundingBox`.
 * west/south/east/north order matches spatialk.
 */
data class GeoBounds(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
)

// ---------------------------------------------------------------------------
// Optional spatialk interop — KEEP ONLY IN :maps MODULE
// ---------------------------------------------------------------------------
// The conversions below are intentionally commented out in :library:map so that
// :library:map does NOT depend on spatialk. If you need to bridge to
// maplibre-compose / spatialk types inside :maps, copy these helpers into
// maps/src/main/java/com/vayunmathur/maps/util/GeoInterop.kt or similar.
//
// fun GeoPoint.toPosition(): org.maplibre.spatialk.geojson.Position =
//     org.maplibre.spatialk.geojson.Position(longitude, latitude)
//
// fun org.maplibre.spatialk.geojson.Position.toGeoPoint(): GeoPoint =
//     GeoPoint(longitude, latitude)
//
// fun GeoBounds.toBoundingBox(): org.maplibre.spatialk.geojson.BoundingBox =
//     org.maplibre.spatialk.geojson.BoundingBox(west, south, east, north)
//
// fun org.maplibre.spatialk.geojson.BoundingBox.toGeoBounds(): GeoBounds =
//     GeoBounds(west, south, east, north)
//
// // maplibre-compose CameraPosition interop (maps app only):
// // fun com.maplibre.compose.camera.CameraPosition.toLibrary(): com.vayunmathur.library.map.CameraPosition = ...
// ---------------------------------------------------------------------------
