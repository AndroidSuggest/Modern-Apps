//! Turns a decoded tile into per-layer triangles.
//!
//! Pure CPU and a pure function: no Vulkan types, no JNI, so the expensive half of a
//! frame is testable on the host and can run on any thread. This is the step the plan
//! expects to be the actual bottleneck — a vector map is slow on the CPU, not the GPU.
//!
//! Reads a `.mamaps` body rather than an MVT tile, and that is most of why the format exists: a
//! feature's `kind` is a `u16` tested against a sorted slice instead of a property-map lookup
//! yielding a `String`, and a part's points are a slice of an already-decoded arena instead of a
//! geometry-command walk. Nothing downstream of here changed — fills are still 2 floats a vertex,
//! strokes 7, and the shaders never saw any of it.

mod arrows;
mod build;
mod buildings;
mod convert;
mod mesh;
mod regions;
mod split;
mod terrain;
mod traffic;

#[cfg(test)]
mod tests;
#[cfg(test)]
mod tests_extra;
#[cfg(test)]
mod tests_extra2;
#[cfg(test)]
mod tests_extra3;
#[cfg(test)]
mod tests_extra4;

pub use build::{build, build_toggled};
pub use mesh::{
    BuildingMesh, CarriagewayMesh, LayerMesh, RegionMesh, ShapedLabel, TerrainMesh, TileMesh,
    TrafficMesh, ROAD_LANE_MIN_ZOOM, TRAFFIC_MIN_ZOOM,
};
