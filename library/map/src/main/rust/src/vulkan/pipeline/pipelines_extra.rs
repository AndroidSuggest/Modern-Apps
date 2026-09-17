use ash::vk;

pub struct Pipelines {
    pub layout: vk::PipelineLayout,
    /// The layout with the atlas descriptor set (set 0) for the symbol pipeline.
    pub symbol_layout: vk::PipelineLayout,
    /// Layout with TWO sampled images (set 0 color, set 1 DEM) for the Moon pipeline.
    pub moon_layout: vk::PipelineLayout,
    /// The Moon descriptor-set layout (both bindings). Owned here so `destroy`
    /// outlives the pool/sets the upload path creates per texture push.
    pub moon_ds_layout: vk::DescriptorSetLayout,
    pub fill: vk::Pipeline,
    pub line: vk::Pipeline,
    /// Road carriageways: a filled surface with the lane markings painted onto it, from
    /// `road_surface.vert`/`.frag` over the 7-float `tess::ribbon` vertex.
    ///
    /// Deliberately a second pipeline rather than a wider [`line`](Self::line). `TrafficMesh` and
    /// the route overlay both ride the 8-float stroke format and the line pipeline, so widening it
    /// to carry an across-road coordinate would charge every one of them for a layer they do not
    /// draw — the tradeoff `tile/geometry.rs` already states about the lane fans this replaces.
    ///
    /// Same fixed-function state as [`line`](Self::line) — [`Depth::Off`], no culling, straight
    /// src-alpha — because a carriageway is flat basemap drawn in layer order like every other
    /// flat 2D layer. Only the vertex format and the shaders differ.
    pub ribbon: vk::Pipeline,
    /// Depth-tested (test + write, `LESS`) variant of [`fill`](Self::fill), for the 3D layers
    /// WS-A (buildings) and WS-G (terrain) add. It reuses the position-only fill shaders so the
    /// render-pass depth path is exercised today; the 3D workstreams build their own pipelines
    /// with the same [`Depth::TestWrite`] against the depth attachment WS0 added to the pass.
    /// The flat 2D layers stay on [`fill`](Self::fill)/[`line`](Self::line) with depth off, so
    /// their output is unchanged.
    pub depth: vk::Pipeline,
    /// The 3D building pipeline (WS-A): the extruded walls and roof caps of the `buildings` layer.
    /// Its own vertex format — position + height + normal + per-vertex ARGB colour — and its own
    /// `building.vert`/`building.frag`, depth-tested ([`Depth::TestWrite`]) so buildings occlude
    /// one another and the basemap at z14+. Takes the push-only [`layout`](Self::layout): it samples
    /// no atlas, its colour is per-vertex. At pitch 0 its vertex shader collapses to the footprint,
    /// so the flat map is unchanged.
    pub building: vk::Pipeline,
/// The 3D terrain pipeline (WS-G): the DEM-displaced ground grid of a tile that carries a
    /// heightmap. Its own vertex format — position + height + surface normal (no per-vertex colour;
    /// the ground colour is the pushed `earth` colour) — and its own `terrain.vert`/`terrain.frag`,
    /// depth-tested ([`Depth::TestWrite`]) so hills occlude one another and let buildings on the far
    /// side of a ridge be hidden by it. Takes the push-only [`layout`](Self::layout): it samples no
    /// atlas. Drawn *before* the flat layer loop, so the flat layers paint over it; at pitch 0 its
    /// vertex shader collapses the grid to the flat footprint, so the overhead map is unchanged.
    pub terrain: vk::Pipeline,
    /// Globe fill: `fill_globe.vert`/`fill_globe.frag` on the fill vertex format,
    /// depth-tested so the near hemisphere wins over the far side and coarser
    /// ancestors lose to finer descendants. Drawn instead of [`fill`](Self::fill)
    /// while the globe is active; the flat pipeline is never bound then.
    pub fill_globe: vk::Pipeline,
    /// Globe line: `line_globe.vert`/`line_globe.frag` on the stroke vertex format,
    /// depth-tested like [`fill_globe`](Self::fill_globe). Drawn instead of
    /// [`line`](Self::line) while the globe is active.
    pub line_globe: vk::Pipeline,
    /// Globe ribbon: `road_surface_globe.vert`/`.frag` on the ribbon vertex format,
    /// depth-tested like [`fill_globe`](Self::fill_globe). Drawn instead of
    /// [`ribbon`](Self::ribbon) while the globe is active.
    pub ribbon_globe: vk::Pipeline,
    /// Globe symbols + icons: `symbol_globe.vert` with the glyph and sprite fragment
    /// shaders, on the billboard vertex format, depth-tested so far-side labels lose.
    /// Drawn instead of [`symbol`](Self::symbol)/[`icon`](Self::icon) on the globe.
    pub symbol_globe: vk::Pipeline,
    pub icon_globe: vk::Pipeline,
    /// Moon raster disc: `moon.vert`/`moon.frag` over the position-only fill
    /// format (the shared unit quad, uploaded once in `Renderer::new`), through
    /// [`moon_layout`](Self::moon_layout) with the color + DEM sets. Depth-tested
    /// so far-side fragments lose; drawn INSTEAD of everything else while the
    /// Moon is active (no vector layers, no overlays, no route).
    pub moon: vk::Pipeline,
    pub symbol: vk::Pipeline,
    /// POI icons. The billboard vertex shader from [`symbol`](Self::symbol) paired with the sprite
    /// fragment shader, on the same 6-float format and the same
    /// [`symbol_layout`](Self::symbol_layout): an icon faces the camera under tilt exactly as its
    /// label does, but samples a picture rather than a distance field. Deliberately the *same*
    /// vertex shader as the text so the two can never disagree about where the anchor projects.
    pub icon: vk::Pipeline,
    /// App markers. The plain on-ground vertex shader on the 4-float
    /// [`MARKER_FLOATS_PER_VERTEX`](crate::tile::symbol::MARKER_FLOATS_PER_VERTEX) format: markers
    /// resolve their quads to clip space on the CPU and draw through an identity matrix, so they
    /// need no per-vertex anchor and no billboard branch.
    pub sprite: vk::Pipeline,
    /// Screen-anchored overlay quads — today only the user puck. Takes the push-only
    /// [`layout`](Self::layout), not [`symbol_layout`](Self::symbol_layout), because it
    /// samples nothing: the puck is drawn analytically from a distance and an angle, so
    /// there is no atlas and no descriptor set.
    ///
    /// Built here rather than bolted onto the renderer so it survives `Renderer::rebuild`,
    /// which destroys and remakes every `Pipelines` on each resize and rotation.
    pub puck: vk::Pipeline,
    /// The region mask: draws the selected region's tessellated shape into the stencil and no
    /// colour at all, so [`scrim`](Self::scrim) can skip those pixels.
    ///
    /// A stencil rather than a clipped polygon because a region arrives as one clipped piece per
    /// tile: rasterising them all into the same stencil unions them for free, whereas cutting the
    /// region out of a viewport quad geometrically needs a polygon boolean, which is what two
    /// earlier attempts at exactly this foundered on.
    pub mask: vk::Pipeline,
    /// The dimming scrim, drawn over the whole viewport wherever the stencil is still zero.
    pub scrim: vk::Pipeline,
}
