package com.vayunmathur.library.map

/**
 * An XYZ raster tile source: a `{z}/{x}/{y}` URL template plus attribution and
 * a zoom range. Uses retina (`@2x`) tiles for crispness — [tileSizePx] is the
 * pixel size of the fetched image (512 for `@2x`).
 */
data class TileSource(
    val urlTemplate: String,
    val attribution: String,
    val minZoom: Int = 0,
    val maxZoom: Int = 20,
    val tileSizePx: Int = 512,
) {
    fun url(z: Int, x: Int, y: Int): String =
        urlTemplate
            .replace("{z}", z.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())

    companion object {
        private const val ATTRIBUTION = "© OpenStreetMap © CARTO"

        /** CARTO Voyager — colorful general-purpose basemap (findfamily/photos). */
        val CartoVoyager = TileSource(
            urlTemplate = "https://basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}@2x.png",
            attribution = ATTRIBUTION,
        )

        /** CARTO Positron (light_all) — muted basemap for data overlays (weather). */
        val CartoPositron = TileSource(
            urlTemplate = "https://basemaps.cartocdn.com/light_all/{z}/{x}/{y}@2x.png",
            attribution = ATTRIBUTION,
        )
    }
}
