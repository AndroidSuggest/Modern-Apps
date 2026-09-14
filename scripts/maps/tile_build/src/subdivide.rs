//! Tile one feature by descending the tile quadtree, instead of once per tile.
//!
//! The tiling loops used to have one shape: ask [`crate::geom::tiles_touched`] for
//! every tile a feature reaches, then clip **the whole feature** against each of
//! them. That is `O(T * V)`, and both factors grow with the feature's geographic
//! extent: a state-sized polygon at z13 has `T` in the hundreds of thousands and
//! `V` in the hundreds of thousands, and every one of those tiles paid a full
//! vertex walk -- including the vast majority that are strictly interior and whose
//! answer is just the tile rectangle.
//!
//! [`subdivide`] walks the quadtree instead:
//!
//! 1. Find the deepest cell that already contains the whole geometry. A building at
//!    z14 gets the leaf itself and no recursion at all, so the leaf clip is the only
//!    clip -- exactly what the old loop did. A coastline gets a shallow cell.
//! 2. At each level, clip the geometry **already clipped to this cell** into each of
//!    the four children, and recurse into the ones that survive.
//! 3. A level-`z` cell rect *is* [`crate::geom::tile_rect`], so the leaf sees the
//!    same rectangle as before.
//!
//! Cost becomes `O(V * (z - level))` for the vertex walks plus `O(T)` small clips at
//! the bottom, rather than `O(V * T)`.
//!
//! ## Why clipping progressively is sound
//!
//! The buffer is the same world-unit constant at every level. A child cell is a
//! subset of its parent, and growing both by the same Minkowski sum keeps that:
//! `child (+) buffer` is a subset of `parent (+) buffer`. Clipping is intersection, so
//! `clip(clip(g, A), B)` is `clip(g, B)` whenever `B` is inside `A`, and the whole
//! descent is that identity applied `z - level` times.
//!
//! Sutherland-Hodgman makes that worth checking rather than assuming. It returns a
//! clipped *concave* ring as a single self-touching ring joined by zero-width slivers
//! (see [`crate::clip`]'s module docs), which is not the intersection as a simple
//! polygon -- so composing through one is not obviously sound.
//! `clipping_twice_is_clipping_once_for_a_nested_rect` searches for a counterexample
//! over random concave rings, random holes and random nested rect pairs, and finds
//! none: the sliver runs along the shared boundary and the next clip either keeps that
//! boundary or removes the sliver with it.
//!
//! It is the same argument [`crate::geom::tiles_touched`]'s bisection rests on: a
//! recursive subdivision is safe exactly when each step preserves what the next needs.
//!
//! ## Points do not descend
//!
//! [`crate::clip::clip_geometry`] on `Points` filters the whole list per tile, which
//! is its own `O(T * V)`. A point's tiles are just the tiles its own padded box
//! covers, so points are bucketed straight into them -- which is what
//! `tiles_touched` followed by a `contains` test worked out to anyway, one point at a
//! time.
//!
//! ## What this costs in output
//!
//! **Not byte-identity to a direct clip, and not the vertices that were expected to
//! cost it.** Clipping to an ancestor cell does insert vertices on that ancestor's
//! boundary, and [`crate::geom::Vertex::boundary`] does stamp them unremovable -- but
//! none of them survive to a leaf as a vertex the leaf's own clip would not have
//! invented. A child cell is a subset of its parent and both are grown by the *same*
//! buffer, so a parent boundary line either coincides with the child's line on that
//! side, when the child sits on that border, or lies strictly outside the child,
//! where the next clip removes it. Coincident lines give coincident crossings.
//!
//! Measured over the corpus in this module's tests, the descent emits **fewer**
//! vertices than a direct clip, not more, so there is no collinear-reduction pass and
//! the archive does not grow. Three differences remain, none of them a shape:
//!
//! | difference | why | visible? |
//! |---|---|---|
//! | a ring's starting vertex | Sutherland-Hodgman keeps its input's rotation, and the descent's input is its parent's answer | same polygon; MVT re-states the start as a `MoveTo` |
//! | fewer collinear spurs | a concave ring's zero-area run along a clip boundary is shorter when built in stages | zero-area either way |
//! | last-bit drift on a line crossing | Liang-Barsky interpolates both coordinates, so a crossing taken off an already-interpolated vertex can land an ULP away | 1e-12 world units against a quantisation grid of 1 |
//!
//! The tests hold the descent to being the same *shape* -- same tiles, same parts,
//! same corners up to rotation, same signed area, same length -- and record the byte
//! divergence rather than asserting it away.

use crate::clip::clip_geometry;
use crate::geom::{self, Geometry, Rect, Vertex};

/// Clip `g` into every tile of zoom `z` it reaches, calling `emit` once per tile.
///
/// `g` must already be in world coordinates for `z` (see [`crate::geom`]'s module
/// docs). `buffer` is the same overspill the old per-tile loop passed to both
/// `tiles_touched` and `tile_rect`, and it is applied at every level of the descent.
///
/// A tile whose clip is empty is not emitted. The old loop reached those tiles and
/// then skipped them, so nothing downstream sees a difference.
///
/// Tiles arrive in quadtree order, which is neither the old `(x, y)` order nor
/// `tile_id` order. Every caller either keys a map by tile or sorts afterwards, so
/// the order is deterministic rather than significant.
pub fn subdivide<V, F>(g: &Geometry<V>, z: u8, extent: u32, buffer: f64, emit: &mut F)
where
    V: Vertex,
    F: FnMut(u64, u64, &Geometry<V>),
{
    if let Geometry::Points(pts) = g {
        bucket_points(pts, z, extent, buffer, emit);
        return;
    }
    let Some(box_) = geom::bounds(g) else { return };
    // The same padded range the old loop's `tiles_touched` computed from, so a
    // feature reaching only into a neighbour's buffer still descends towards it.
    let Some(range) = geom::tile_range(&box_, z, extent, buffer) else { return };

    // The deepest cell holding the whole range is the one whose tile indices agree
    // once the low bits are dropped.
    let mut shift = 0u32;
    while (range.x0 >> shift) != (range.x1 >> shift) || (range.y0 >> shift) != (range.y1 >> shift) {
        shift += 1;
    }
    let level = z - shift as u8;
    let (cx, cy) = (range.x0 >> shift, range.y0 >> shift);

    // The start cell already contains the geometry, so this clip inserts nothing
    // unless the feature runs off the edge of the grid -- where the old loop's edge
    // tile cut it at the same coordinate, because a cell on the grid's border shares
    // that border with its leaves.
    let clipped = clip_geometry(g, &cell_rect(level, cx, cy, z, extent, buffer));
    if is_empty(&clipped) {
        return;
    }
    walk(&clipped, level, cx, cy, z, extent, buffer, emit);
}

/// One quadtree cell's clip rect in world coordinates, grown by `buffer`.
///
/// At `level == z` this is [`geom::tile_rect`] to the bit: `2^0` is 1, so the
/// arithmetic below is the same arithmetic.
fn cell_rect(level: u8, cx: u64, cy: u64, z: u8, extent: u32, buffer: f64) -> Rect {
    let side = extent as f64 * (1u64 << (z - level)) as f64;
    let (ox, oy) = (cx as f64 * side, cy as f64 * side);
    Rect {
        min_x: ox - buffer,
        min_y: oy - buffer,
        max_x: ox + side + buffer,
        max_y: oy + side + buffer,
    }
}

/// Descend from a cell whose clipped geometry is `g`, emitting at the leaves.
#[allow(clippy::too_many_arguments)]
fn walk<V, F>(
    g: &Geometry<V>,
    level: u8,
    cx: u64,
    cy: u64,
    z: u8,
    extent: u32,
    buffer: f64,
    emit: &mut F,
) where
    V: Vertex,
    F: FnMut(u64, u64, &Geometry<V>),
{
    if level == z {
        emit(cx, cy, g);
        return;
    }
    for (dx, dy) in [(0u64, 0u64), (1, 0), (0, 1), (1, 1)] {
        let (nx, ny) = (cx * 2 + dx, cy * 2 + dy);
        let clipped = clip_geometry(g, &cell_rect(level + 1, nx, ny, z, extent, buffer));
        if is_empty(&clipped) {
            continue;
        }
        walk(&clipped, level + 1, nx, ny, z, extent, buffer, emit);
    }
}

/// Whether a clip left anything at all.
///
/// A part-count test is enough: [`clip_geometry`] already drops lines below two
/// vertices and rings below three, so a surviving part is a drawable part.
fn is_empty<V>(g: &Geometry<V>) -> bool {
    match g {
        Geometry::Points(pts) => pts.is_empty(),
        Geometry::Lines(lines) => lines.is_empty(),
        Geometry::Polygons(polys) => polys.is_empty(),
    }
}

/// Bucket points into the tiles whose buffered rects hold them.
///
/// Deliberately the padded box rather than just the tile the coordinate floors
/// into: a point three units from a tile edge belongs in the neighbour's buffer too,
/// which is what keeps a label from being clipped at a tile join. That is exactly
/// the set `tiles_touched` listed and `clip_geometry`'s `contains` test then kept.
///
/// One `Vec` of `(tile, point)` pairs sorted into runs, rather than a map of tiles to
/// point lists. A `Points` feature is usually a single point, and this crate's
/// standard for a per-feature path is set by `bucket_feature` threading its record
/// buffer through from the caller -- a `HashMap` plus a `Vec` per tile per feature
/// would be three allocations to place one node. The sort is stable, so points keep
/// their input order within a tile.
fn bucket_points<V, F>(pts: &[V], z: u8, extent: u32, buffer: f64, emit: &mut F)
where
    V: Vertex,
    F: FnMut(u64, u64, &Geometry<V>),
{
    let mut spread: Vec<((u64, u64), V)> = Vec::with_capacity(pts.len());
    for p in pts {
        let (x, y) = p.xy();
        let box_ = Rect { min_x: x, min_y: y, max_x: x, max_y: y };
        let Some(range) = geom::tile_range(&box_, z, extent, buffer) else { continue };
        for tile in range.iter() {
            spread.push((tile, *p));
        }
    }
    spread.sort_by_key(|&(tile, _)| tile);
    // One `Geometry` reused across tiles, its inner `Vec` refilled rather than
    // reallocated: `emit` takes a borrow, so handing over an owned vector per tile
    // would give the allocation away and leave nothing to reuse.
    let mut holder = Geometry::Points(Vec::new());
    let mut at = 0usize;
    while at < spread.len() {
        let tile = spread[at].0;
        let end = at + spread[at..].partition_point(|&(t, _)| t == tile);
        let Geometry::Points(one) = &mut holder else { unreachable!("built as points") };
        one.clear();
        one.extend(spread[at..end].iter().map(|&(_, p)| p));
        emit(tile.0, tile.1, &holder);
        at = end;
    }
}

include!("subdivide_part1.rs");
include!("subdivide_part2.rs");
include!("subdivide_part3.rs");