use tilecodec::mamaps::body::ROOF_ORIENT_ACROSS;

/// An oriented bounding box of a ring, aligned to the roof direction, in which the pitched roofs
/// are laid out. `point` maps `(cu, av)` in `0..=1` (across the slopes, along the ridge) to a
/// tile-local footprint point.
pub(crate) struct OrientedBox {
    pub(crate) cross: (f32, f32),
    pub(crate) along: (f32, f32),
    pub(crate) cross_min: f32,
    pub(crate) cross_span: f32,
    pub(crate) along_min: f32,
    pub(crate) along_span: f32,
}

impl OrientedBox {
    pub(crate) fn of(ring: &[(f32, f32)], roof_dir_rad: f32, orientation: u8) -> OrientedBox {
        // The ridge runs along the roof direction by default; `across` swaps the axes so the ridge
        // runs perpendicular instead. `cross` is the axis the slopes descend along.
        let (rs, rc) = roof_dir_rad.sin_cos();
        let ridge = (rc, rs);
        let perp = (-rs, rc);
        let (along, cross) = if orientation == ROOF_ORIENT_ACROSS {
            (perp, ridge)
        } else {
            (ridge, perp)
        };
        let mut cross_min = f32::MAX;
        let mut cross_max = f32::MIN;
        let mut along_min = f32::MAX;
        let mut along_max = f32::MIN;
        for &(x, y) in ring {
            let c = x * cross.0 + y * cross.1;
            let al = x * along.0 + y * along.1;
            cross_min = cross_min.min(c);
            cross_max = cross_max.max(c);
            along_min = along_min.min(al);
            along_max = along_max.max(al);
        }
        // A zero span would divide by zero; a hair of span keeps a needle-thin footprint finite.
        let cross_span = (cross_max - cross_min).max(f32::EPSILON);
        let along_span = (along_max - along_min).max(f32::EPSILON);
        OrientedBox {
            cross,
            along,
            cross_min,
            cross_span,
            along_min,
            along_span,
        }
    }

    /// The tile-local footprint point at oriented-box coordinates `(cu, av)`, each in `0..=1`.
    pub(crate) fn point(&self, cu: f32, av: f32) -> (f32, f32) {
        let c = self.cross_min + cu * self.cross_span;
        let al = self.along_min + av * self.along_span;
        (
            self.cross.0 * c + self.along.0 * al,
            self.cross.1 * c + self.along.1 * al,
        )
    }
}
