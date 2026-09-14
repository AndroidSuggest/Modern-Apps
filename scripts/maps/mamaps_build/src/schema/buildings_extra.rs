/// Metres to decimetres, rounded and clamped to `u16`; negative or non-finite yields `None`.
pub(crate) fn dm_of(metres: f64) -> Option<u16> {
    if !metres.is_finite() || metres < 0.0 {
        return None;
    }
    Some((metres * 10.0).round().min(u16::MAX as f64) as u16)
}
