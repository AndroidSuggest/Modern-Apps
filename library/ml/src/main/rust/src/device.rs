//! Android Vulkan device bootstrap (Task 2, owner: device-dev).
//!
//! Owns physical-device selection and logical-device creation for
//! `:library:ml` on Android. The flow is:
//!
//! 1. [`Entry::load`] opens `libvulkan.so` (the ash 0.38 default on Android).
//! 2. A [`VkInstance`][ash::Instance] is created with
//!    [`API_VERSION_1_2`][ash::vk::API_VERSION_1_2].
//! 3. [`best_compute_device`] enumerates the physical devices, keeps only
//!    those with a compute queue family, and scores the rest
//!    `DISCRETE(3) > INTEGRATED(2) > VIRTUAL(1) > CPU/OTHER(0)`.
//! 4. [`init_android`] creates one compute queue on the winner, enables the
//!    integer-dot-product / cooperative-matrix extensions when present, and
//!    snapshots the optional caps into [`DeviceCaps`].
//!
//! [`probe_bitmask`] is the lightweight sibling used by
//! [`crate::probe::probe_device`]: instance + enumeration only, no logical
//! device, so the JNI probe stays cheap. Every public entry here is fallible
//! and never panics; a missing loader or device is a logged value, not a
//! crash, because the Kotlin side degrades to CPU.
//!
//! This [`DeviceCaps`] is the Vulkan bootstrap snapshot (raw version,
//! vendor, queue family, optional features). It is distinct from
//! `crate::shaders_mobile::DeviceCaps`, which is the host-side shader-policy
//! view; convert between them at the session layer, not here.

use std::ffi::CStr;

use ash::{Entry, vk};
use thiserror::Error;

/// Khronos vendor ID for Qualcomm (Adreno GPUs).
pub const VENDOR_QUALCOMM: u32 = 0x5143;
/// Khronos vendor ID for ARM (Mali GPUs).
pub const VENDOR_ARM: u32 = 0x13B5;

/// Snapshot of the optional capabilities queried at bootstrap.
///
/// All feature flags default to `false` when the extension or feature query
/// reports them absent; the device is still usable through the WGSL fallback
/// path owned by Task 4.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DeviceCaps {
    /// Raw Vulkan `apiVersion` of the selected physical device.
    pub version: u32,
    /// Raw Vulkan `vendorID` (see [`VENDOR_QUALCOMM`], [`VENDOR_ARM`]).
    pub vendor: u32,
    /// Queue family index of the compute queue created on the device.
    pub compute_queue: u32,
    /// `shaderIntegerDotProduct` from `VK_KHR_shader_integer_dot_product`.
    pub int_dot: bool,
    /// `cooperativeMatrix` from `VK_KHR_cooperative_matrix`.
    pub coopmat: bool,
    /// `storageBuffer8BitAccess` from Vulkan 1.2 core features.
    pub storage8: bool,
    /// `vulkanMemoryModel` from Vulkan 1.2 core features.
    ///
    /// Queried alongside `storage8` because the integer kernels require
    /// both; kept as its own flag so callers can distinguish them.
    pub memory_model: bool,
    /// `subgroupSize` from `VkPhysicalDeviceSubgroupProperties`.
    pub subgroup: u32,
}

impl DeviceCaps {
    /// Whether the vendor is Qualcomm (Adreno).
    pub const fn is_qualcomm(&self) -> bool {
        self.vendor == VENDOR_QUALCOMM
    }

    /// Whether the vendor is ARM (Mali).
    pub const fn is_arm(&self) -> bool {
        self.vendor == VENDOR_ARM
    }

    /// Whether the device reports Vulkan 1.2 or newer.
    pub const fn has_vulkan12(&self) -> bool {
        self.version >= vk::API_VERSION_1_2
    }
}

/// Failures reported while bootstrapping the Android Vulkan device.
///
/// Each variant carries the driver message where one exists so callers can
/// log the cause and fall back to CPU execution.
#[derive(Debug, Error)]
pub enum DeviceError {
    /// The Vulkan loader (`libvulkan.so`) could not be opened.
    #[error("vulkan loader unavailable: {0}")]
    Loader(String),
    /// `vkCreateInstance` failed.
    #[error("vulkan instance creation failed: {0}")]
    Instance(String),
    /// No physical device with a compute queue family was found.
    #[error("no compute-capable Vulkan device found")]
    NoComputeDevice,
    /// `vkCreateDevice` failed on the selected physical device.
    #[error("logical device creation failed: {0}")]
    LogicalDevice(String),
}

/// Score a physical-device type for selection.
///
/// `DISCRETE_GPU(3) > INTEGRATED_GPU(2) > VIRTUAL_GPU(1) > CPU/OTHER(0)`,
/// so a lavapipe CPU device is strictly the last resort.
pub fn score_device_type(device_type: vk::PhysicalDeviceType) -> i32 {
    match device_type {
        vk::PhysicalDeviceType::DISCRETE_GPU => 3,
        vk::PhysicalDeviceType::INTEGRATED_GPU => 2,
        vk::PhysicalDeviceType::VIRTUAL_GPU => 1,
        _ => 0,
    }
}

/// Find the first queue family with a compute queue, if any.
///
/// Returns the family index as `u32`. Never panics; an unrepresentable index
/// is skipped rather than truncated.
pub fn find_compute_queue(families: &[vk::QueueFamilyProperties]) -> Option<u32> {
    for (index, family) in families.iter().enumerate() {
        if family.queue_flags.contains(vk::QueueFlags::COMPUTE) {
            if let Ok(queue_family) = u32::try_from(index) {
                return Some(queue_family);
            }
        }
    }
    None
}

/// Read a null-terminated Vulkan device name without panicking.
#[allow(unsafe_code)]
fn device_name(props: &vk::PhysicalDeviceProperties) -> String {
    // SAFETY: `device_name` is a null-terminated fixed-size array populated by
    // the driver per the Vulkan spec; the physical device outlives this call.
    let name = unsafe { CStr::from_ptr(props.device_name.as_ptr()) };
    name.to_string_lossy().into_owned()
}

/// Whether the device exposes the named device extension.
#[allow(unsafe_code)]
fn has_extension(
    instance: &ash::Instance,
    physical: vk::PhysicalDevice,
    wanted: &CStr,
) -> bool {
    // SAFETY: `physical` was enumerated from `instance` and is still live;
    // a query failure means "absent", never a panic.
    let extensions = unsafe { instance.enumerate_device_extension_properties(physical) };
    match extensions {
        Ok(list) => list.iter().any(|entry| {
            // SAFETY: `extension_name` is a null-terminated driver string.
            let name = unsafe { CStr::from_ptr(entry.extension_name.as_ptr()) };
            name == wanted
        }),
        Err(_) => false,
    }
}

/// Pick the best compute-capable physical device plus its queue family.
///
/// Returns `(device, queue_family, score)`. Pure selection: no logical
/// device is created here.
#[allow(unsafe_code)]
fn best_compute_device(
    instance: &ash::Instance,
) -> Option<(vk::PhysicalDevice, u32, i32)> {
    // SAFETY: `instance` is a live instance owned by the caller.
    let devices = unsafe { instance.enumerate_physical_devices() }.ok()?;
    let mut best: Option<(vk::PhysicalDevice, u32, i32)> = None;
    for physical in devices {
        // SAFETY: `physical` was just enumerated from `instance`.
        let families =
            unsafe { instance.get_physical_device_queue_family_properties(physical) };
        let queue_family = match find_compute_queue(&families) {
            Some(queue_family) => queue_family,
            None => continue,
        };
        // SAFETY: `physical` is still live.
        let props = unsafe { instance.get_physical_device_properties(physical) };
        let score = score_device_type(props.device_type);
        let improves = match best {
            Some((_, _, current)) => score > current,
            None => true,
        };
        if improves {
            best = Some((physical, queue_family, score));
        }
    }
    best
}

/// Create a Vulkan 1.2 instance for device enumeration and creation.
#[allow(unsafe_code)]
fn create_instance(entry: &Entry) -> Result<ash::Instance, DeviceError> {
    let app_info = vk::ApplicationInfo::default().api_version(vk::API_VERSION_1_2);
    let create_info = vk::InstanceCreateInfo::default().application_info(&app_info);
    // SAFETY: `create_info` borrows only the locally owned `app_info`; no
    // layers, extensions, or allocator callbacks are passed, and the returned
    // instance is destroyed via `destroy_instance` (or moved into `Device`).
    let instance = unsafe { entry.create_instance(&create_info, None) };
    instance.map_err(|err| DeviceError::Instance(format!("{err}")))
}

/// Query the optional capability flags for `physical`.
#[allow(unsafe_code)]
fn query_caps(
    instance: &ash::Instance,
    physical: vk::PhysicalDevice,
    queue_family: u32,
) -> DeviceCaps {
    // SAFETY: `physical` was enumerated from `instance` and is live.
    let props = unsafe { instance.get_physical_device_properties(physical) };

    let mut dot_features = vk::PhysicalDeviceShaderIntegerDotProductFeaturesKHR::default();
    let mut coop_features = vk::PhysicalDeviceCooperativeMatrixFeaturesKHR::default();
    let mut vk12_features = vk::PhysicalDeviceVulkan12Features::default();
    let mut features2 = vk::PhysicalDeviceFeatures2::default()
        .push_next(&mut dot_features)
        .push_next(&mut coop_features)
        .push_next(&mut vk12_features);
    // SAFETY: `features2` chain borrows only live local structs.
    unsafe { instance.get_physical_device_features2(physical, &mut features2) };

    let mut subgroup_props = vk::PhysicalDeviceSubgroupProperties::default();
    let mut props2 = vk::PhysicalDeviceProperties2::default().push_next(&mut subgroup_props);
    // SAFETY: `props2` chain borrows only the live local `subgroup_props`.
    unsafe { instance.get_physical_device_properties2(physical, &mut props2) };

    DeviceCaps {
        version: props.api_version,
        vendor: props.vendor_id,
        compute_queue: queue_family,
        int_dot: dot_features.shader_integer_dot_product == vk::TRUE,
        coopmat: coop_features.cooperative_matrix == vk::TRUE,
        storage8: vk12_features.storage_buffer8_bit_access == vk::TRUE,
        memory_model: vk12_features.vulkan_memory_model == vk::TRUE,
        subgroup: subgroup_props.subgroup_size,
    }
}

/// Vulkan device owned by the `:library:ml` bootstrap.
///
/// Holds the loader [`Entry`], the [`VkInstance`][ash::Instance], the logical
/// [`ash::Device`], and the compute [`queue`][vk::Queue] together so no
/// handle outlives the object it was loaded from. Thread confinement follows
/// the Vulkan queue rules; sharing across threads is owned by Task 3.
pub struct Device {
    entry: Entry,
    instance: ash::Instance,
    physical: vk::PhysicalDevice,
    logical: ash::Device,
    queue: vk::Queue,
    queue_family: u32,
    caps: DeviceCaps,
    name: String,
}

impl Device {
    /// Borrow the logical device for queue and command work.
    pub const fn logical(&self) -> &ash::Device {
        &self.logical
    }

    /// The compute queue created on [`Device::queue_family`].
    pub const fn queue(&self) -> vk::Queue {
        self.queue
    }

    /// Queue family index the compute queue belongs to.
    pub const fn queue_family(&self) -> u32 {
        self.queue_family
    }

    /// The selected physical device handle.
    pub const fn physical(&self) -> vk::PhysicalDevice {
        self.physical
    }

    /// Capability snapshot taken at creation time.
    pub const fn caps(&self) -> &DeviceCaps {
        &self.caps
    }

    /// Driver-reported device name.
    pub fn name(&self) -> &str {
        &self.name
    }
}

#[allow(unsafe_code)]
impl Drop for Device {
    /// Release the logical device, then the instance.
    fn drop(&mut self) {
        // SAFETY: `logical` was created from `instance`/`physical` in
        // `init_android` and has not been destroyed; no commands are in flight
        // when the owning `Device` is dropped.
        unsafe {
            let _ = self.logical.device_wait_idle();
            self.logical.destroy_device(None);
            self.instance.destroy_instance(None);
        }
        // `entry` (the `libvulkan.so` guard) is still alive here: field
        // destructors run only after `Drop::drop` returns, and the explicit
        // destroys above already released every handle loaded from it.
        // `ash` handles hold no `Drop` of their own, so nothing further runs.
        let _ = &self.entry;
    }
}

/// Bootstrap the best Android Vulkan device with a compute queue.
///
/// Loads `libvulkan.so` via [`Entry::load`], creates a Vulkan 1.2 instance,
/// selects the highest-scoring compute-capable physical device, enables the
/// integer-dot-product and cooperative-matrix extensions when the driver
/// reports them, and returns the owned [`Device`] plus its [`DeviceCaps`].
/// Any failure yields [`DeviceError`] with the driver message; nothing
/// panics, so JNI callers can fall back to CPU.
#[allow(unsafe_code)]
pub fn init_android() -> Result<(Device, DeviceCaps), DeviceError> {
    // SAFETY: `dlopen`ing the system loader is the documented `ash` pattern;
    // the returned `Entry` keeps the library mapped while owned by `Device`.
    let entry = unsafe { Entry::load() }
        .map_err(|err| DeviceError::Loader(format!("{err}")))?;
    let instance = create_instance(&entry)?;

    let (physical, queue_family, _) = match best_compute_device(&instance) {
        Some(selected) => selected,
        None => {
            // SAFETY: `instance` is live and owned here; nothing else was created.
            unsafe { instance.destroy_instance(None) };
            return Err(DeviceError::NoComputeDevice);
        }
    };
    let caps = query_caps(&instance, physical, queue_family);
    let name = {
        // SAFETY: `physical` is live on `instance`.
        let props = unsafe { instance.get_physical_device_properties(physical) };
        device_name(&props)
    };

    let dot_present = has_extension(
        &instance,
        physical,
        vk::KHR_SHADER_INTEGER_DOT_PRODUCT_NAME,
    );
    let coop_present =
        has_extension(&instance, physical, vk::KHR_COOPERATIVE_MATRIX_NAME);

    let enable_dot = dot_present && caps.int_dot;
    let enable_coop = coop_present && caps.coopmat && caps.storage8 && caps.memory_model;

    let mut dot_features = vk::PhysicalDeviceShaderIntegerDotProductFeaturesKHR::default();
    let mut coop_features = vk::PhysicalDeviceCooperativeMatrixFeaturesKHR::default();
    let mut vk12_features = vk::PhysicalDeviceVulkan12Features::default();
    if enable_dot {
        // Request the feature bit explicitly: the default struct leaves it
        // `FALSE`, which would enable the extension without the feature.
        dot_features.shader_integer_dot_product = vk::TRUE;
    }
    if enable_coop {
        // Only the features the integer kernels declare in SPIR-V.
        coop_features.cooperative_matrix = vk::TRUE;
        vk12_features = vk12_features
            .vulkan_memory_model(true)
            .storage_buffer8_bit_access(true)
            .shader_int8(true);
    }

    let mut enabled_exts: Vec<*const std::ffi::c_char> = Vec::new();
    if enable_dot {
        enabled_exts.push(vk::KHR_SHADER_INTEGER_DOT_PRODUCT_NAME.as_ptr());
    }
    if enable_coop {
        enabled_exts.push(vk::KHR_COOPERATIVE_MATRIX_NAME.as_ptr());
    }

    let queue_priorities = [1.0f32];
    let queue_info = vk::DeviceQueueCreateInfo::default()
        .queue_family_index(queue_family)
        .queue_priorities(&queue_priorities);
    let queue_infos = [queue_info];
    let mut device_info = vk::DeviceCreateInfo::default()
        .queue_create_infos(&queue_infos)
        .enabled_extension_names(&enabled_exts);
    if enable_dot {
        device_info = device_info.push_next(&mut dot_features);
    }
    if enable_coop {
        device_info = device_info
            .push_next(&mut coop_features)
            .push_next(&mut vk12_features);
    }
    // SAFETY: `device_info` borrows only live locals (`queue_infos`,
    // `enabled_exts`, feature structs); the device is destroyed in `Drop`.
    let logical = unsafe { instance.create_device(physical, &device_info, None) };
    let logical = match logical {
        Ok(logical) => logical,
        Err(err) => {
            // SAFETY: `instance` is live and owned here.
            unsafe { instance.destroy_instance(None) };
            return Err(DeviceError::LogicalDevice(format!("{err}")));
        }
    };
    // SAFETY: `queue_family` was reported by the driver and passed to
    // `create_device` with one queue, so index 0 is valid.
    let queue = unsafe { logical.get_device_queue(queue_family, 0) };

    log::info!(
        "vulkan device: {name} (vendor 0x{:04x}, family {queue_family}): int_dot={} coopmat={} storage8={} subgroup={}",
        caps.vendor,
        caps.int_dot,
        caps.coopmat,
        caps.storage8,
        caps.subgroup,
    );

    let device = Device {
        entry,
        instance,
        physical,
        logical,
        queue,
        queue_family,
        caps,
        name,
    };
    Ok((device, caps))
}

/// Probe Vulkan support and return the JNI capability bitmask.
///
/// Instance plus physical-device enumeration only — no logical device is
/// created, so this stays cheap enough for the JNI `probe` call. Bits match
/// [`crate::probe`] (`LOADER/COMPUTE/COOPMAT16/VULKAN12`); a missing loader
/// yields `0` plus a `log::info` line, never a panic.
#[allow(unsafe_code)]
pub fn probe_bitmask() -> i32 {
    // Bit values mirror `crate::probe` to keep JNI compatible without a
    // cross-module constant dependency.
    const LOADER: i32 = 1;
    const COMPUTE: i32 = 2;
    const COOPMAT16: i32 = 4;
    const VULKAN12: i32 = 8;

    // SAFETY: as in `init_android`; the entry guard lives until function end.
    let entry = match unsafe { Entry::load() } {
        Ok(entry) => entry,
        Err(err) => {
            log::info!("vulkan loader unavailable: {err}");
            return 0;
        }
    };
    let instance = match create_instance(&entry) {
        Ok(instance) => instance,
        Err(err) => {
            log::info!("vulkan instance unavailable: {err}");
            return LOADER;
        }
    };
    let selected = best_compute_device(&instance);
    let bits = match selected {
        Some((physical, _, _)) => {
            // SAFETY: `physical` is live on `instance`.
            let props = unsafe { instance.get_physical_device_properties(physical) };
            let mut bits = LOADER | COMPUTE;
            if props.api_version >= vk::API_VERSION_1_2 {
                bits |= VULKAN12;
            }
            if has_extension(&instance, physical, vk::KHR_COOPERATIVE_MATRIX_NAME) {
                let mut coop = vk::PhysicalDeviceCooperativeMatrixFeaturesKHR::default();
                let mut features2 =
                    vk::PhysicalDeviceFeatures2::default().push_next(&mut coop);
                // SAFETY: `features2` borrows only the live local `coop`.
                unsafe { instance.get_physical_device_features2(physical, &mut features2) };
                if coop.cooperative_matrix == vk::TRUE {
                    bits |= COOPMAT16;
                }
            }
            bits
        }
        None => LOADER,
    };
    // SAFETY: `instance` is live and owned here; the entry guard drops after.
    unsafe { instance.destroy_instance(None) };
    bits
}
