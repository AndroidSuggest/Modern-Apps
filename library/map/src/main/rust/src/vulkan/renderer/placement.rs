use super::{
    anchors_for, box_inputs, kind_name, AcceptSet, Overlay, PlacedHit, PlacementKey, Renderer,
    QUAD_INDICES,
};
use crate::camera::Camera;
use crate::marker::Marker;
use crate::style::{Layer, LayerKind};
use ash::vk;
use std::collections::HashMap;
use std::ops::RangeInclusive;
use std::time::Instant;

/// How long a collision answer may be reused while the camera moves.
///
/// The placer is quadratic in accepted boxes plus per-glyph box projection for every curved
/// label — all of it on the Choreographer callback. During a pan a frame lands every ~16 ms
/// with a slightly moved camera, so re-placing every frame is what the panning jank is made
/// of. Within this window a moved camera reuses the last accept-set (labels hold their
/// relative order; boxes are still re-projected per frame in `refresh_placed`, so what draws
/// tracks the camera and only the *collision outcome* lags). Past it — or on any non-center
/// change (zoom, bearing, pitch, tiles, layers, filter) — the pass re-runs. A tap during the
/// window picks against boxes projected for the current frame, so hit-testing never lags.
/// hit-testing never lags.
pub(super) const PLACE_REUSE_MS: u128 = 300;

impl Renderer {
    /// The region whose shape covers this point at the requested administrative level.
    ///
    /// The caller has a place — a tapped city label or a search result — and needs the relation
    /// id of the region it names, which the `places` feature does not carry. Containment is the
    /// link: a city label sits inside its own boundary.
    ///
    /// # Why the level is not optional
    ///
    /// Containment alone answers the wrong question. Every label sits inside a whole stack of
    /// regions — a city inside a county inside a state inside a country — so a point lookup has
    /// to be told which rung of that stack the caller means. Preferring the smallest was the
    /// first attempt and it picks the deepest rung every time: tapping a state's label selects
    /// whichever county the label's anchor happens to land in.
    ///
    /// `levels` is the inclusive band the selection maps to (see `kind_for` in the tiler's
    /// boundary schema, which is what put these numbers in the archive). Within the band the
    /// smallest containing shape still wins, so a city inside a larger city resolves inward.
    ///
    /// Ties are broken by the deeper level and then by the lower id, never by iteration order.
    /// A city and the county it is coterminous with have near-identical areas, and leaving that
    /// to a hash map's ordering makes the same tap pick differently from one frame to the next.
    ///
    /// **Only the label's own band is consulted.** There is deliberately no any-level fallback: a
    /// city label maps to the city band, and if no boundary in that band contains the point (the
    /// city's relation is off-screen, or its anchor sits just outside its own outline) the honest
    /// answer is no mask — masking whatever county or state happens to contain the point instead
    /// outlines a region the label never named, which is the whole complaint this guards against.
    ///
    /// `None` when no region in the band covers the point.
    pub fn region_at(&self, lon: f64, lat: f64, levels: RangeInclusive<u16>) -> Option<u64> {
        self.smallest_containing(lon, lat, &levels)
    }

    fn smallest_containing(&self, lon: f64, lat: f64, levels: &RangeInclusive<u16>) -> Option<u64> {
        // A region is clipped into one fragment per tile it crosses, and `region.area` is only
        // that fragment's area. Comparing fragments across tiles picked whichever tile happened
        // to hold the smaller slice, so the masked region flipped as tiles loaded and clipped
        // differently. Sum every fragment of an id first, so the comparison is against each
        // region's whole on-screen size; then choose the smallest region that actually contains
        // the point (containment is only ever true in the one tile the point falls in).
        let mut level_of: HashMap<u64, u16> = HashMap::new();
        let mut area_of: HashMap<u64, f32> = HashMap::new();
        let mut hit: HashMap<u64, bool> = HashMap::new();
        for tile in self.tiles.values() {
            let local = tile_local(lon, lat, tile.z, tile.x, tile.y);
            for region in &tile.regions {
                if !levels.contains(&region.level) {
                    continue;
                }
                level_of.insert(region.id, region.level);
                *area_of.entry(region.id).or_insert(0.0) += region.area;
                if let Some((u, v)) = local {
                    if region.rings.iter().any(|ring| contains(ring, u, v)) {
                        hit.insert(region.id, true);
                    }
                }
            }
        }
        // Smallest summed area wins; ties break to the deeper level, then the lower id — a total
        // order, so the pick never depends on hash-map iteration.
        hit.into_keys().min_by(|&a, &b| {
            let area = |id: u64| area_of.get(&id).copied().unwrap_or(0.0);
            let level = |id: u64| level_of.get(&id).copied().unwrap_or(0);
            area(a)
                .total_cmp(&area(b))
                .then_with(|| level(b).cmp(&level(a)))
                .then(a.cmp(&b))
        })
    }

    /// The per-frame symbol pre-pass: one collision candidate per shaped label
    /// of every drawing symbol layer, placed once, globally.
    ///
    /// Collision padding widens with zoom-out (see
    /// [`collision_padding_px`]): at low zoom boxes overlap eagerly so only
    /// the most important places survive, matching MapLibre's ~10 cities at
    /// z6; at street zoom padding relaxes toward the drawn box.
    pub(super) fn place_symbols(
        &self,
        camera: &Camera,
        layers: &[Layer],
        ordered: &[u64],
        extent: vk::Extent2D,
        filter: &crate::style::KindFilter,
    ) -> AcceptSet {
        use crate::tile::placement;
        let key = PlacementKey {
            center_lon: camera.center_lon.to_bits(),
            center_lat: camera.center_lat.to_bits(),
            zoom: camera.zoom.to_bits(),
            bearing: camera.bearing_deg.to_bits(),
            pitch: camera.pitch_deg.to_bits(),
            width_dp: camera.width_dp.to_bits(),
            height_dp: camera.height_dp.to_bits(),
            density: camera.density.to_bits(),
            extent: (extent.width, extent.height),
            filter: filter.clone(),
            tiles: ordered
                .iter()
                .map(|key| {
                    (
                        *key,
                        self.tiles
                            .get(key)
                            .map(|t| t.uploaded_at)
                            .unwrap_or(0.0)
                            .to_bits(),
                    )
                })
                .collect(),
            layers: layers.len(),
        };
        if let Some((cached, accepted, at)) = self.placement_cache.borrow().as_ref() {
            if *cached == key {
                return accepted.clone();
            }
            // Same everything but the centre: a pan in progress. Reuse the last accept-set
            // for a short window rather than re-running the quadratic pass every frame.
            let mut moved = cached.clone();
            moved.center_lon = key.center_lon;
            moved.center_lat = key.center_lat;
            if moved == key && at.elapsed().as_millis() < PLACE_REUSE_MS {
                return accepted.clone();
            }
        }
        let mut candidates: Vec<placement::SegmentedCandidate> = Vec::new();
        let camera_z = camera.zoom.floor().clamp(0.0, 22.0) as u8;
        for (index, layer) in layers.iter().enumerate() {
            if layer.kind != LayerKind::Symbol {
                continue;
            }
            if !layer.draws_at_focused(camera_z, layer.focused_by(filter)) {
                continue;
            }
            // Device px, matching `record_symbol`: `extent` below is device px, so a
            // Dp text size here would size every collision box at 1/density and let
            // labels that visibly overlap all survive the placer. Resolved per label,
            // because the size depends on the place's population rank.
            if !layer.text_visible_at(camera.zoom) {
                continue;
            }
            let (primary, alternate) = anchors_for(layer);
            for key in ordered {
                let Some(tile) = self.tiles.get(key) else {
                    continue;
                };
                // A tile deeper than the camera's own level is a stand-in kept so a zoom-out does
                // not blank the map (`select::DESCENDANT_DEPTH`). It contributes its geometry, but
                // not its labels: a POI's zoom floor is enforced only by which pyramid level
                // carries it, so a retained z14 tile would otherwise draw its bus stops at camera
                // z12. Every other layer is saved by its own camera-zoom floor; the `poi-*` layers
                // declare none, which is why transit stops were the visible symptom.
                if tile.z > camera_z {
                    continue;
                }
                let tile_clip = camera.tile_to_clip(tile.z, tile.x, tile.y);
                let tile_span_px = camera.tile_span_px(tile.z);
                let wh = (extent.width, extent.height);
                // Task-9 rank gating: at low UI zoom only high-pop localities
                // Rank gating BEFORE collision: a hamlet must not
                // become a candidate at all - collision alone can't thin
                // hundreds of towns to the major-city set. The camera zoom is
                // the UI zoom now that the tile grid is 512, so selection
                // matches MapLibre's per-zoom set directly.
                let min_pop = placement::locality_min_pop(camera.zoom);
                for (label_idx, label) in tile
                    .labels
                    .iter()
                    .enumerate()
                    .filter(|(_, l)| l.layer_index == index)
                {
                    if label.rank == 2 && label.pop < min_pop {
                        continue;
                    }
                    let inputs = box_inputs(layer, label, camera);
                    let id = placement::candidate_id(tile.z, tile.x, tile.y, index, label_idx);
                    // A curved label collides as the row of oriented per-glyph boxes it draws; a
                    // point label as one axis-aligned box (plus its variable-anchor alternate). Both
                    // go into one `place_segmented` pass so they collide with each other.
                    let (boxes, alternate_boxes) = if let Some(centreline) = &label.centreline {
                        let Some(line) = label.lines.first() else {
                            continue;
                        };
                        let ppfu = inputs.text_px / crate::tile::glyph::UP_EM as f32 / tile_span_px;
                        let placed = crate::tess::text::layout_along_line(line, centreline, ppfu);
                        let boxes = placement::curved_boxes(
                            &placed,
                            tile_clip,
                            wh,
                            inputs.text_px,
                            inputs.pad_px,
                        );
                        if boxes.is_empty() {
                            continue; // does not fit its line this frame: nothing to place.
                        }
                        (boxes, None)
                    } else {
                        // The anchor projects with the perspective divide (see
                        // `placement::anchored_rect`): under tilt the box sits where the
                        // billboarded label draws, and distant labels compress onto
                        // overlapping boxes the placer thins by rank. A label whose anchor
                        // is on or behind the eye has no screen position and is skipped —
                        // it cannot be a candidate, so drawing it is unrepresentable here.
                        let Some(primary_rect) =
                            placement::anchored_rect(label.anchor, tile_clip, wh, &inputs, primary)
                        else {
                            continue;
                        };
                        let primary_box = placement::Obb::from_rect(primary_rect);
                        // Both anchors share the label's one anchor point, so they project
                        // together: a primary that projected means the alternate does too,
                        // and `and_then` only guards the impossible split.
                        let alt = alternate.and_then(|second| {
                            placement::anchored_rect(label.anchor, tile_clip, wh, &inputs, second)
                                .map(|r| vec![placement::Obb::from_rect(r)])
                        });
                        (vec![primary_box], alt)
                    };
                    candidates.push(placement::SegmentedCandidate {
                        id,
                        rank: label.rank,
                        pop: label.pop,
                        boxes,
                        alternate: alternate_boxes,
                    });
                }
            }
        }
        // Keyed by candidate id, valued by the anchor the placer settled on and **where in
        // acceptance order it landed**. `place_segmented` returns its winners in priority order and
        // a `HashMap` would throw that away, which is what made `pick_labels`' "topmost first" a
        // claim rather than a fact.
        let accepted: AcceptSet = placement::place_segmented(&candidates)
            .into_iter()
            .enumerate()
            .map(|(order, (id, flipped))| (id, (flipped, order as u32)))
            .collect();
        *self.placement_cache.borrow_mut() = Some((key, accepted.clone(), Instant::now()));
        accepted
    }

    /// Task-17 pick: the placed labels of the last frame whose screen boxes
    /// contain the query box (device px), in placement order (topmost first).
    /// A linear scan — hundreds of labels, no index needed. Called from the
    /// JNI pick path with Dp already converted to device px by the caller.
    pub fn pick_labels(&self, query: (f32, f32, f32, f32)) -> Vec<PlacedHit> {
        let (qx0, qy0, qx1, qy1) = query;
        self.placed
            .borrow()
            .iter()
            .filter(|h| h.rect.0 <= qx1 && h.rect.2 >= qx0 && h.rect.1 <= qy1 && h.rect.3 >= qy0)
            .cloned()
            .collect()
    }

    /// The marker id under the device-pixel `(x, y)`, or `0` when the tap hit no marker.
    ///
    /// The GPU-picking counterpart of [`pick_labels`](Self::pick_labels): where labels are picked
    /// against the CPU snapshot of the last placed frame, markers are picked by rendering their
    /// ids into an offscreen `R32_UINT` buffer with the last frame's camera and reading back the
    /// tapped pixel (see [`crate::vulkan::pick`]). That is what keeps a pin tappable under tilt,
    /// where its screen box is no longer a plain projection of its lon/lat.
    ///
    /// Returns the marker's own id (the value the host set on it), so the host maps the tap back to
    /// its feature without matching on position. `0` covers "no marker here", "no frame drawn yet",
    /// and a pick that could not run — all of which the host treats as "fall through to the next
    /// probe", exactly as an empty [`pick_labels`](Self::pick_labels) result is treated.
    pub fn pick_at(&mut self, x: u32, y: u32) -> u64 {
        let Some(camera) = self.last_camera else {
            return 0;
        };
        // The markers this frame would draw, in draw order, so the topmost pin wins the pixel.
        let markers: Vec<Marker> = self
            .overlays
            .iter()
            .filter_map(|overlay| match overlay {
                Overlay::Markers(m) => Some(m.iter().copied()),
                _ => None,
            })
            .flatten()
            .collect();
        if markers.is_empty() {
            return 0;
        }
        let extent = self.swapchain.extent;
        let quad_indices = QUAD_INDICES.len() as u32;
        match unsafe {
            self.pick.at(
                &self.context,
                self.command_pool,
                self.quad.vertices.buffer,
                self.quad.indices.buffer,
                quad_indices,
                extent,
                &camera,
                &markers,
                x,
                y,
            )
        } {
            Ok(id) => id,
            Err(e) => {
                eprintln!("pick_at failed: {e}");
                0
            }
        }
    }

    /// Task-17 pick snapshot: rebuild [`placed`](Self::placed) from the
    /// frame's accept-set. Boxes are recomputed with the same inputs the
    /// pre-pass used (same text size, same padding) so pick boxes match drawn
    /// boxes; anchor lon/lat comes from unprojecting the tile-local anchor
    /// through the tile's world position at the camera zoom.
    pub(super) fn refresh_placed(
        &self,
        camera: &Camera,
        layers: &[Layer],
        accepted: &HashMap<u64, (bool, u32)>,
        extent: vk::Extent2D,
    ) {
        use crate::tile::placement;
        let mut placed: Vec<(u32, PlacedHit)> = Vec::new();
        for tile in self.tiles.values() {
            // Tile origin in world px at the camera zoom: tile (x,y) at ITS
            // OWN zoom would misplace overzoomed ancestors, but every
            // resident tile here is at the selected zoom (see select), so
            // tile_span at tile.z is the tile's own screen size and the
            // anchor offsets within it directly.
            let span_dp = camera.tile_span_dp(tile.z);
            let origin = camera.viewport_origin();
            let tile_wx = tile.x as f64 * span_dp;
            let tile_wy = tile.y as f64 * span_dp;
            for (label_idx, label) in tile.labels.iter().enumerate() {
                let id =
                    placement::candidate_id(tile.z, tile.x, tile.y, label.layer_index, label_idx);
                let Some(&(flipped, order)) = accepted.get(&id) else {
                    continue;
                };
                let Some(layer) = layers.get(label.layer_index) else {
                    continue;
                };
                // Device px, as in `place_symbols` — this rebuilds the same boxes for
                // the pick path, so it has to agree with them, including which anchor
                // the placer settled on.
                let text_px = layer.text_size_for(camera.zoom, label.pop) * camera.density;
                if text_px <= 0.0 {
                    continue;
                }
                let (primary, alternate) = anchors_for(layer);
                let anchor = if flipped {
                    alternate.unwrap_or(primary)
                } else {
                    primary
                };
                let tile_clip = camera.tile_to_clip(tile.z, tile.x, tile.y);
                // Same pitch-aware projection as the pre-pass, so pick boxes match drawn
                // boxes under tilt; an unprojectable anchor has no box and no hit.
                let Some(rect) = placement::anchored_rect(
                    label.anchor,
                    tile_clip,
                    (extent.width, extent.height),
                    &box_inputs(layer, label, camera),
                    anchor,
                ) else {
                    continue;
                };
                // Anchor tile-local → world px (Dp) → lon/lat.
                let wx = tile_wx + label.anchor.0 as f64 * span_dp;
                let wy = tile_wy + label.anchor.1 as f64 * span_dp;
                let (lon, lat) = crate::camera::unproject(wx, wy, camera.zoom);
                let _ = origin;
                placed.push((
                    order,
                    PlacedHit {
                        rect,
                        layer_index: label.layer_index,
                        name: label.name.clone(),
                        // The feature's own kind, not the layer's first whitelist entry — that
                        // reported `restaurant` for every `poi-food` hit, `stadium` for every
                        // civic one, and so on for all four multi-kind layers.
                        kind: kind_name(label.kind),
                        feature_id: label.feature_id,
                        lon,
                        lat,
                    },
                ));
            }
        }
        // `self.tiles` is a `HashMap`, so the walk above visits tiles in arbitrary order. Sorted
        // back into the placer's acceptance order here, which is what makes "topmost first" true
        // of what `pick_labels` returns — and therefore what makes a tap resolve to the label
        // actually drawn on top rather than to whichever tile the hasher happened to yield first.
        placed.sort_by_key(|(order, _)| *order);
        *self.placed.borrow_mut() = placed.into_iter().map(|(_, hit)| hit).collect();
    }
}

/// Where `lon`/`lat` falls inside tile `z/x/y`, in tile-local 0..1, or `None` if it is outside.
///
/// Web Mercator, matching the projection the tiler cut the archive with.
fn tile_local(lon: f64, lat: f64, z: u8, x: u32, y: u32) -> Option<(f32, f32)> {
    let n = f64::from(1u32 << z);
    let sin = lat.to_radians().sin().clamp(-0.9999, 0.9999);
    let world_x = (lon + 180.0) / 360.0 * n;
    let world_y = (0.5 - ((1.0 + sin) / (1.0 - sin)).ln() / (4.0 * std::f64::consts::PI)) * n;
    let u = world_x - f64::from(x);
    let v = world_y - f64::from(y);
    (0.0..=1.0).contains(&u).then_some(())?;
    (0.0..=1.0).contains(&v).then_some(())?;
    Some((u as f32, v as f32))
}

/// Even-odd point-in-polygon over a closed ring.
fn contains(ring: &[(f32, f32)], u: f32, v: f32) -> bool {
    let mut inside = false;
    for window in ring.windows(2) {
        let (x0, y0) = window[0];
        let (x1, y1) = window[1];
        if (y0 > v) != (y1 > v) && u < (x1 - x0) * (v - y0) / (y1 - y0) + x0 {
            inside = !inside;
        }
    }
    inside
}

/// Screen-space quad matrix for a globe overlay anchor: the lon/lat's screen
/// point (Dp, via [`Camera::globe_anchor_to_screen`]) centred in a `radius_dp`
/// quad, in the same shape as [`Camera::screen_quad_to_clip`] so the puck and
/// vehicle-ring draws share one contract.
pub(super) fn globe_screen_quad(camera: &Camera, sx_dp: f64, sy_dp: f64, radius_dp: f64) -> [f32; 16] {
    let kx = 2.0 / camera.width_dp as f64;
    let ky = 2.0 / camera.height_dp as f64;
    let cx = sx_dp * kx - 1.0;
    let cy = sy_dp * ky - 1.0;
    [
        (kx * radius_dp) as f32,
        0.0,
        0.0,
        0.0, //
        0.0,
        (ky * radius_dp) as f32,
        0.0,
        0.0, //
        0.0,
        0.0,
        1.0,
        0.0, //
        cx as f32,
        cy as f32,
        0.0,
        1.0,
    ]
}
