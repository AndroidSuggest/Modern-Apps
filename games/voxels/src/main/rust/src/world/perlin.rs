//! Perlin noise — a self-contained replacement for the `noise` crate (which was
//! used *only* for `Perlin` + `NoiseFn` here and pulled in a whole stale `rand`
//! 0.7 ecosystem: `rand`, `rand_chacha` 0.2, `rand_core` 0.5, `rand_hc`,
//! `rand_xorshift`, `ppv-lite86`, `getrandom` 0.1 — 8 crates for a 256-byte
//! permutation table).
//!
//! The noise math (gradient scheme, quintic fade, √N scaling, hash fold, output
//! range and clamp) is reproduced **exactly** from `noise` 0.8, so 2D/3D output
//! is character- and range-identical. The only difference is how the per-seed
//! permutation table is built: `noise` seeded a `XorShiftRng` and ran rand 0.7's
//! Fisher–Yates; we use a SplitMix64-seeded Fisher–Yates instead. Terrain for a
//! given seed therefore differs from the old build going forward — already-saved
//! chunks are loaded from disk and are unaffected.

/// Mirror of `noise::NoiseFn<T>`: a noise source sampled at a point of type `T`.
pub trait NoiseFn<T> {
    fn get(&self, point: T) -> f64;
}

/// Classic 2D/3D Perlin gradient noise, output in `[-1, 1]`.
#[derive(Clone)]
pub struct Perlin {
    /// 256-entry permutation table (same role as `noise`'s `PermutationTable`).
    values: [u8; 256],
}

impl Perlin {
    /// Deterministically build a permutation table from a `u32` seed.
    pub fn new(seed: u32) -> Self {
        let mut values = [0u8; 256];
        for (i, v) in values.iter_mut().enumerate() {
            *v = i as u8;
        }

        // SplitMix64 stream seeded from `seed`.
        let mut state = 0x9E37_79B9_7F4A_7C15u64
            ^ (seed as u64).wrapping_mul(0x2545_F491_4F6C_DD1D);
        let mut next = || -> u64 {
            state = state.wrapping_add(0x9E37_79B9_7F4A_7C15);
            let mut z = state;
            z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
            z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
            z ^ (z >> 31)
        };

        // Fisher–Yates.
        for i in (1..256).rev() {
            let j = (next() % (i as u64 + 1)) as usize;
            values.swap(i, j);
        }
        Perlin { values }
    }

    /// Hash fold identical to `noise`'s `PermutationTable::hash`.
    #[inline]
    fn hash2(&self, x: isize, y: isize) -> usize {
        let index = self.values[(x & 0xff) as usize] as usize ^ (y & 0xff) as usize;
        self.values[index] as usize
    }

    #[inline]
    fn hash3(&self, x: isize, y: isize, z: isize) -> usize {
        let a = self.values[(x & 0xff) as usize] as usize ^ (y & 0xff) as usize;
        let index = self.values[a] as usize ^ (z & 0xff) as usize;
        self.values[index] as usize
    }
}

/// Quintic fade `6t⁵ − 15t⁴ + 10t³` (matches `noise`'s `map_quintic`).
#[inline]
fn quintic(t: f64) -> f64 {
    t * t * t * (t * (t * 6.0 - 15.0) + 10.0)
}

/// 2D gradient · vector, matching `noise::core::perlin::perlin_2d`.
#[inline]
fn grad2(perm: usize, x: f64, y: f64) -> f64 {
    match perm & 0b11 {
        0 => x + y,
        1 => -x + y,
        2 => x - y,
        _ => -x - y,
    }
}

/// 3D gradient · vector, matching `noise::core::perlin::perlin_3d`.
#[inline]
fn grad3(perm: usize, x: f64, y: f64, z: f64) -> f64 {
    match perm & 0b1111 {
        0 | 12 => x + y,
        1 | 13 => -x + y,
        2 => x - y,
        3 => -x - y,
        4 => x + z,
        5 => -x + z,
        6 => x - z,
        7 => -x - z,
        8 => y + z,
        9 | 14 => -y + z,
        10 => y - z,
        _ => -y - z, // 11 | 15
    }
}

impl NoiseFn<[f64; 2]> for Perlin {
    fn get(&self, point: [f64; 2]) -> f64 {
        // 1/(sqrt(2)/2) = sqrt(2).
        const SCALE_FACTOR: f64 = std::f64::consts::SQRT_2;

        let [px, py] = point;
        let fx = px.floor();
        let fy = py.floor();
        let cx = fx as isize;
        let cy = fy as isize;
        let dx = px - fx;
        let dy = py - fy;

        let g00 = grad2(self.hash2(cx, cy), dx, dy);
        let g10 = grad2(self.hash2(cx + 1, cy), dx - 1.0, dy);
        let g01 = grad2(self.hash2(cx, cy + 1), dx, dy - 1.0);
        let g11 = grad2(self.hash2(cx + 1, cy + 1), dx - 1.0, dy - 1.0);

        let u = quintic(dx);
        let v = quintic(dy);

        let k0 = g00;
        let k1 = g10 - g00;
        let k2 = g01 - g00;
        let k3 = g00 + g11 - g10 - g01;
        let unscaled = k0 + k1 * u + k2 * v + k3 * u * v;

        (unscaled * SCALE_FACTOR).clamp(-1.0, 1.0)
    }
}

impl NoiseFn<[f64; 3]> for Perlin {
    fn get(&self, point: [f64; 3]) -> f64 {
        // 2/sqrt(3), high-precision constant per the noise crate.
        const SCALE_FACTOR: f64 = 1.154_700_538_379_251_5;

        let [px, py, pz] = point;
        let fx = px.floor();
        let fy = py.floor();
        let fz = pz.floor();
        let cx = fx as isize;
        let cy = fy as isize;
        let cz = fz as isize;
        let dx = px - fx;
        let dy = py - fy;
        let dz = pz - fz;

        let g000 = grad3(self.hash3(cx, cy, cz), dx, dy, dz);
        let g100 = grad3(self.hash3(cx + 1, cy, cz), dx - 1.0, dy, dz);
        let g010 = grad3(self.hash3(cx, cy + 1, cz), dx, dy - 1.0, dz);
        let g110 = grad3(self.hash3(cx + 1, cy + 1, cz), dx - 1.0, dy - 1.0, dz);
        let g001 = grad3(self.hash3(cx, cy, cz + 1), dx, dy, dz - 1.0);
        let g101 = grad3(self.hash3(cx + 1, cy, cz + 1), dx - 1.0, dy, dz - 1.0);
        let g011 = grad3(self.hash3(cx, cy + 1, cz + 1), dx, dy - 1.0, dz - 1.0);
        let g111 = grad3(self.hash3(cx + 1, cy + 1, cz + 1), dx - 1.0, dy - 1.0, dz - 1.0);

        let a = quintic(dx);
        let b = quintic(dy);
        let c = quintic(dz);

        let k0 = g000;
        let k1 = g100 - g000;
        let k2 = g010 - g000;
        let k3 = g001 - g000;
        let k4 = g000 + g110 - g100 - g010;
        let k5 = g000 + g101 - g100 - g001;
        let k6 = g000 + g011 - g010 - g001;
        let k7 = g100 + g010 + g001 + g111 - g000 - g110 - g101 - g011;

        let unscaled =
            k0 + k1 * a + k2 * b + k3 * c + k4 * a * b + k5 * a * c + k6 * b * c + k7 * a * b * c;

        (unscaled * SCALE_FACTOR).clamp(-1.0, 1.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn output_in_range_and_deterministic() {
        let p = Perlin::new(1337);
        let p2 = Perlin::new(1337);
        for i in 0..50 {
            let x = i as f64 * 0.37;
            let y = i as f64 * -0.21 + 3.0;
            let z = i as f64 * 0.11;
            let a = p.get([x, y]);
            let b = p.get([x, y, z]);
            assert!((-1.0..=1.0).contains(&a));
            assert!((-1.0..=1.0).contains(&b));
            // Same seed → identical output.
            assert_eq!(a, p2.get([x, y]));
            assert_eq!(b, p2.get([x, y, z]));
        }
    }

    #[test]
    fn integer_lattice_points_are_zero() {
        // Perlin noise is exactly 0 at integer lattice points.
        let p = Perlin::new(42);
        assert!(p.get([3.0, -5.0]).abs() < 1e-12);
        assert!(p.get([1.0, 2.0, 3.0]).abs() < 1e-12);
    }

    #[test]
    fn different_seeds_differ() {
        // A single point can coincide across seeds (only 4 gradient choices),
        // so compare the fields over a range of points.
        let a = Perlin::new(1);
        let b = Perlin::new(2);
        let any_diff = (0..64).any(|i| {
            let x = i as f64 * 0.13 + 0.5;
            let y = i as f64 * 0.29 + 0.5;
            a.get([x, y]) != b.get([x, y])
        });
        assert!(any_diff, "distinct seeds produced identical noise fields");
    }
}
