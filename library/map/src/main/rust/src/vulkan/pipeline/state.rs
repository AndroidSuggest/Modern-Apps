/// How a pipeline uses the stencil attachment.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Stencil {
    /// Neither tests nor writes: every pipeline that draws the map itself.
    Ignore,
    /// Writes 1 wherever it draws, and writes no colour. The region mask.
    Write,
    /// Draws only where the stencil is still 0 — outside the region. The scrim.
    TestOutside,
}

/// How a pipeline uses the depth attachment WS0 added to the render pass.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Depth {
    /// Neither tests nor writes depth: every flat 2D layer. Its output is exactly what it was
    /// before the depth attachment existed, which is what keeps pitch-0 rendering byte-identical.
    Off,
    /// Tests and writes depth with `LESS`: the 3D layers (buildings, terrain) that must occlude
    /// one another. Only meaningful under a perspective camera, where the clip matrix produces a
    /// real per-vertex depth.
    TestWrite,
}
