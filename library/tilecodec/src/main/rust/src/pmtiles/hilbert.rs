/// Number of tiles in every zoom below `z`, i.e. the first tile id at `z`.
/// `(4^z - 1) / 3`.
pub fn zoom_base(z: u8) -> u64 {
    // Closed form via the geometric series; exact in u64 up to z=31.
    ((1u64 << (2 * z as u32)) - 1) / 3
}

/// Rotate/flip a quadrant. The canonical Hilbert helper.
fn rot(n: u64, x: &mut u64, y: &mut u64, rx: u64, ry: u64) {
    if ry == 0 {
        if rx == 1 {
            *x = n.wrapping_sub(1).wrapping_sub(*x);
            *y = n.wrapping_sub(1).wrapping_sub(*y);
        }
        std::mem::swap(x, y);
    }
}

/// `(z, x, y)` -> PMTiles tile id.
pub fn tile_id(z: u8, x: u64, y: u64) -> u64 {
    let n = 1u64 << z;
    let (mut x, mut y) = (x, y);
    let mut d = 0u64;
    let mut s = n / 2;
    while s > 0 {
        let rx = u64::from(x & s > 0);
        let ry = u64::from(y & s > 0);
        d += s * s * ((3 * rx) ^ ry);
        rot(n, &mut x, &mut y, rx, ry);
        s /= 2;
    }
    zoom_base(z) + d
}

/// PMTiles tile id -> `(z, x, y)`.
pub fn tile_zxy(id: u64) -> (u8, u64, u64) {
    let mut z = 0u8;
    // Walk up until the next zoom's base passes the id. Bounded by z=31.
    while z < 31 && id >= zoom_base(z + 1) {
        z += 1;
    }
    let mut t = id - zoom_base(z);
    let n = 1u64 << z;
    let (mut x, mut y) = (0u64, 0u64);
    let mut s = 1u64;
    while s < n {
        let rx = 1 & (t / 2);
        let ry = 1 & (t ^ rx);
        rot(s, &mut x, &mut y, rx, ry);
        x += s * rx;
        y += s * ry;
        t /= 4;
        s *= 2;
    }
    (z, x, y)
}

