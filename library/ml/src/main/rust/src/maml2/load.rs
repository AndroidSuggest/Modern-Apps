//! Load a MAML v2 file into an executable plan plus an uploadable blob.
//!
//! The production half of the `maml2` loader: where `verify`/`infer`/`lower`
//! are the pipeline stages, this is the caller that owns the file bytes,
//! runs the stages per entry point, and hands [`Net`] a [`Plan`] plus a
//! [`Blob`] it can upload. The bridge calls one `Model` per asset; variable-
//! shape nets re-call [`Model::load`] on rebuild rather than retaining plans.
//!
//! [`Net`]: crate::vulkan::run::Net

use crate::maml2::{infer, lower, verify};
use crate::nets::Plan;
use crate::weights::Blob;

/// A parsed v2 file, owning its bytes.
///
/// Verification runs at parse: a file that fails structural checks never
/// becomes a `Model`. Inference and lowering run per entry point in
/// [`Model::load`], because each entry lowers to its own plan over the same
/// weights.
pub struct Model {
    bytes: Vec<u8>,
}

/// The weights half of a loaded entry point: the blob in the plan's address
/// space plus the payload extents segmentation needs.
pub struct WeightsBlob {
    blob: Vec<u8>,
    extents: Vec<(u64, u64)>,
}

impl Blob for WeightsBlob {
    fn data_len(&self) -> u64 {
        self.blob.len() as u64
    }

    fn extents(&self) -> Vec<(u64, u64)> {
        self.extents.clone()
    }

    fn read_at(&self, offset: u64, into: &mut [u8]) -> Result<(), String> {
        let start = usize::try_from(offset).map_err(|_| "a data offset overflowed usize")?;
        let end = start.checked_add(into.len()).ok_or("a data range overflowed")?;
        let src = self.blob.get(start..end).ok_or_else(|| {
            format!("bytes {start}..{end} of a {}-byte v2 blob", self.blob.len())
        })?;
        into.copy_from_slice(src);
        Ok(())
    }
}

impl Model {
    /// Parse and structurally verify `bytes` as a MAML v2 file.
    pub fn parse(bytes: Vec<u8>) -> Result<Model, String> {
        verify::verify(&bytes)?;
        Ok(Model { bytes })
    }

    /// Entry point names, in file order.
    pub fn entries(&self) -> Result<Vec<String>, String> {
        let verified = verify::verify(&self.bytes)?;
        let model = verified.model;
        let entries = model.entry_points().ok_or("a model with no entry points")?;
        Ok((0..entries.len())
            .map(|i| entries.get(i).name().unwrap_or("?").to_string())
            .collect())
    }

    /// Lower entry `entry` to its plan plus the weights blob in the plan's
    /// address space.
    ///
    /// Runs inference over every graph (the model-wide digest gate covers
    /// all of them), then lowers the one entry. The blob is owned here and
    /// dropped after upload — [`Net`] retains nothing host-side — so a
    /// rebuild re-calls this rather than reusing a stale plan.
    ///
    /// [`Net`]: crate::vulkan::run::Net
    pub fn load(&self, entry: usize) -> Result<(Plan, WeightsBlob), String> {
        self.load_shaped(entry, None)
    }

    /// [`Model::load`] with live input dims for a rebuild (variable-shape
    /// nets): `live` parallels the entry graph's inputs as `[c, h, w]` rows.
    /// Axes marked variable in the file accept any live length `<= max`;
    /// every other axis must equal the recorded dims. See
    /// [`infer::infer_shaped`].
    pub fn load_shaped(
        &self,
        entry: usize,
        live: Option<&[[i32; 3]]>,
    ) -> Result<(Plan, WeightsBlob), String> {
        let verified = verify::verify(&self.bytes)?;
        let inferred = match live {
            Some(dims) => infer::infer_shaped(&verified, entry, dims)?,
            None => infer::infer(&verified)?,
        };
        let entry_inferred = inferred
            .iter()
            .find(|graph| graph.graph == entry)
            .ok_or_else(|| format!("entry graph {entry} of {} was not inferred", inferred.len()))?;
        let bridge = lower::V2Weights::new(&verified)?;
        let plan = lower::lower(&verified, entry_inferred, &bridge, entry)?;
        // Payload extents for segmentation: one range per buffered tensor,
        // from the bridge's placement map.
        let extents = bridge.extents();
        let blob = bridge.into_blob();
        Ok((plan, WeightsBlob { blob, extents }))
    }
}
