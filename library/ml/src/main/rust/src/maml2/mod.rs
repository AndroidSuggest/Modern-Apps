//! The MAML v2 loader.
//!
//! MAML v2 (`MAM2`, FlatBuffers) carries an SSA graph plus weights in one
//! file. See `library/ml/MAML2_SPEC.md` for the contract and
//! `schema/maml2.fbs` for the schema of record. This module is the other half
//! of the converter's contract: verify, shape/layout inference, lowering,
//! fusion, memory planning, barrier scheduling, and pipeline specialisation.
//!
//! The generated bindings come from `flatc` in `build.rs`, so neither this
//! module nor the converter hand-writes the layout.

/// The file format version this loader reads. Unequal means reject: there is
/// no minor/major game and no v1 fallback (spec section 9.1).
pub const FORMAT_VERSION: u32 = 1;

/// The op catalog version this loader implements. Files naming a newer
/// opset are rejected loudly at open (spec section 5.3).
pub const OPSET_VERSION: u32 = 1;

// The generated bindings use `unsafe` internally (verified flatbuffer access)
// and carry their own `#[allow]`s for style lints. This crate does not opt
// into the workspace lint table, so no capping attribute is needed here.
#[allow(missing_docs)]
mod generated {
    include!(concat!(env!("OUT_DIR"), "/maml2_generated.rs"));
}

pub use generated::maml_2 as fb;

pub mod emit;
pub mod infer;
pub mod load;
pub mod lower;
#[cfg(test)]
pub mod parity;
pub mod verify;
