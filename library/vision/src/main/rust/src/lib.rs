//! Shared computer-vision primitives, extracted from the camera app's panorama
//! stitcher so the measure app's visual-inertial odometry engine can reuse them.
//!
//! Everything here is deliberately dependency-free apart from an optional JPEG
//! decoder behind the `jpeg` feature: the linear algebra, image buffers, feature
//! detection and homography estimation are all hand-rolled so the whole CV stack
//! stays auditable and adds no transitive crates.

pub mod linalg;
pub mod imgbuf;
pub mod features;
pub mod geometry;
pub mod camera;
