//! Vulkan-accelerated ONNX inference for `:library:ml`.
//!
//! This crate is the JNI boundary between the Kotlin inference handles
//! (`VulkanBridge`, `VulkanSessions` and friends in
//! `com.vayunmathur.library.ml`, loaded as `ml_vulkan`) and GPU execution.
//! The Kotlin side degrades gracefully when this library — or a Vulkan
//! device — is absent, so every entry point reached from Kotlin must be
//! fallible and never panic.
//!
//! # Layout
//!
//! * [`tensors`] — host-side tensor packing helpers shared by every session.
//! * [`shaders_mobile`] — mobile shader + pipeline fallback policy.
//! * [`device`] — Android Vulkan device bootstrap (instance, device, queue).
//! * [`probe`] — Vulkan device capability probing behind `VulkanBridge.probe`.
//! * [`vulkan_session`] — model loading and inference on the Vulkan device.
//! * [`jni_bridge`] — the JNI entry points Kotlin calls into.
//!
//! # Features
//!
//! The `vulkan` feature (off by default) pulls in `onnx-vulkan` and enables
//! GPU execution ([`probe`], [`vulkan_session`] and [`jni_bridge`]).
//! [`tensors`], [`shaders_mobile`] and [`memory`] are always compiled:
//! host-side helpers with no GPU dependency, so host checks and unit tests
//! stay dependency-free.

/// Host-side tensor packing helpers shared by every session.
///
/// Always compiled, even without the `vulkan` feature.
pub mod tensors;

/// Mobile shader + pipeline fallback policy (device caps, shader-path
/// selection, workgroups, pipeline cache, chunked dispatch, warmup).
///
/// Always compiled, even without the `vulkan` feature: it is host-side policy
/// with no GPU dependency, like [`tensors`].
pub mod shaders_mobile;

/// Mobile memory budget helpers (chunked weight upload, staging pool,
/// peak-RSS accounting, unified-memory hint, ORT fallback signal).
///
/// Always compiled, even without the `vulkan` feature: host-side planning
/// with no GPU dependency, like [`tensors`].
pub mod memory;

/// Android Vulkan device bootstrap (instance, device, queue, caps).
#[cfg(feature = "vulkan")]
pub mod device;

/// Vulkan device capability probing behind `VulkanBridge.probe`.
#[cfg(feature = "vulkan")]
pub mod probe;

/// Model loading and inference on the Vulkan device.
#[cfg(feature = "vulkan")]
pub mod vulkan_session;

/// The JNI entry points Kotlin calls into.
#[cfg(feature = "vulkan")]
pub mod jni_bridge;
