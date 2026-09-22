package com.vayunmathur.library.map

/**
 * Which rung of the administrative stack a region mask meant.
 *
 * @deprecated The mask is now the label's baked `regionId` passed straight through
 *   (`Long?` from `regionMaskFor` to `setRegionMask`): the tile's `region_links` table links
 *   each place label to its outline at build time, so no point/level guess is needed.
 *   Kept until the follow-up removes it; nothing references it anymore.
 *
 * A mask used to be asked for by point, because nothing in the archive linked a place label
 * to its outline. But a point is contained by every region above it at once — a city sits
 * inside a county inside a state inside a country — so the point alone could not say which
 * outline was meant, and the level band disambiguated. The bands were OSM `admin_level`
 * values, matching the names the tiler's boundary schema gives them (`kind_for`): 1-2
 * country, 3-4 region, 5-6 county, 7+ locality.
 */
@Deprecated("Baked-id path: pass the label's regionId (Long?) straight through instead.")
enum class RegionLevel(val min: Int, val max: Int) {
    /** A country. */
    COUNTRY(1, 2),

    /** A state, province or other first-level division. */
    REGION(3, 4),

    /** A city, town or neighbourhood.
     *
     * Deliberately starts above the county band. A city and the county sharing its name are
     * near enough the same shape that preferring the smaller one picks between them by
     * accident, and the same tap could resolve differently twice running.
     */
    LOCALITY(7, 12),
}

/**
 * A request to dim everything outside one administrative region.
 *
 * @deprecated The mask is now a baked `regionId` (`Long?`): `[position]` + `[level]` travelled
 *   together because neither identified a region on its own, and the id does. Kept until the
 *   follow-up removes it; nothing references it anymore.
 */
@Deprecated("Baked-id path: pass the label's regionId (Long?) straight through instead.")
data class RegionMask(val position: GeoPoint, val level: RegionLevel)
