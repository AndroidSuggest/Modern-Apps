//! Minimal linear-algebra layer replacing the `nalgebra` dependency (which pulled
//! in ~9 transitive crates: simba, wide, safe_arch, bytemuck, approx, paste,
//! num-rational, nalgebra-macros, …). This is a *drop-in* for exactly the subset
//! the camera stitcher used, so the call sites only had to swap `use nalgebra::…`
//! for `use crate::linalg::…`.
//!
//! Scope (everything actually used by bundle/estimator/sphere/wave/night/camera):
//!   * `Matrix3` / `Vector3` / `RowVector3` fixed 3×3 algebra
//!   * `Matrix3::try_inverse` (closed-form adjugate / determinant)
//!   * `Matrix3::svd(true, true)` → `{ u, v_t }` (via one symmetric-eigen of MᵀM)
//!   * `Matrix3::symmetric_eigen` → `{ eigenvalues, eigenvectors }` (cyclic Jacobi)
//!   * `DMatrix` / `DVector` with `.lu().solve(&b)` (partial-pivot LU)
//!
//! The two `svd` callers only ever compute `u * v_t`, the orthogonal *polar
//! factor* of the matrix — which is unique and independent of any internal SVD
//! sign/column-order convention, so this reproduces nalgebra's result to
//! floating-point rounding.
//!
//! `T` is a phantom scalar so existing `Matrix3<f64>` / `Vector3<f64>`
//! annotations keep compiling unchanged; only `f64` is ever instantiated.

use std::marker::PhantomData;
use std::ops::{AddAssign, Div, DivAssign, Index, IndexMut, Mul, Neg};

// ===========================================================================
// Matrix3 (row-major, 3×3)
// ===========================================================================

#[derive(Clone, Copy, Debug)]
pub struct Matrix3<T = f64> {
    /// Row-major: `d[r * 3 + c]`.
    d: [f64; 9],
    _p: PhantomData<T>,
}

impl Matrix3<f64> {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        m00: f64, m01: f64, m02: f64,
        m10: f64, m11: f64, m12: f64,
        m20: f64, m21: f64, m22: f64,
    ) -> Self {
        Matrix3 {
            d: [m00, m01, m02, m10, m11, m12, m20, m21, m22],
            _p: PhantomData,
        }
    }

    pub fn zeros() -> Self {
        Matrix3 { d: [0.0; 9], _p: PhantomData }
    }

    pub fn identity() -> Self {
        Matrix3::new(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    }

    /// Build from three row vectors (mirrors `nalgebra::Matrix3::from_rows`).
    pub fn from_rows(rows: &[RowVector3<f64>]) -> Self {
        let mut d = [0.0; 9];
        for (r, row) in rows.iter().take(3).enumerate() {
            d[r * 3] = row.d[0];
            d[r * 3 + 1] = row.d[1];
            d[r * 3 + 2] = row.d[2];
        }
        Matrix3 { d, _p: PhantomData }
    }

    #[inline]
    fn at(&self, r: usize, c: usize) -> f64 {
        self.d[r * 3 + c]
    }

    pub fn transpose(&self) -> Self {
        Matrix3::new(
            self.d[0], self.d[3], self.d[6],
            self.d[1], self.d[4], self.d[7],
            self.d[2], self.d[5], self.d[8],
        )
    }

    /// Column `c` as an owned vector.
    pub fn column(&self, c: usize) -> Vector3<f64> {
        Vector3::new(self.d[c], self.d[3 + c], self.d[6 + c])
    }

    fn set_column(&mut self, c: usize, v: Vector3<f64>) {
        self.d[c] = v.x;
        self.d[3 + c] = v.y;
        self.d[6 + c] = v.z;
    }

    pub fn determinant(&self) -> f64 {
        let m = &self.d;
        m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6])
            + m[2] * (m[3] * m[7] - m[4] * m[6])
    }

    /// Closed-form 3×3 inverse via the adjugate; `None` if singular.
    pub fn try_inverse(&self) -> Option<Self> {
        let m = &self.d;
        let (a, b, c) = (m[0], m[1], m[2]);
        let (d, e, f) = (m[3], m[4], m[5]);
        let (g, h, i) = (m[6], m[7], m[8]);

        // Cofactors.
        let a00 = e * i - f * h;
        let a01 = -(d * i - f * g);
        let a02 = d * h - e * g;
        let a10 = -(b * i - c * h);
        let a11 = a * i - c * g;
        let a12 = -(a * h - b * g);
        let a20 = b * f - c * e;
        let a21 = -(a * f - c * d);
        let a22 = a * e - b * d;

        let det = a * a00 + b * a01 + c * a02;
        if !det.is_finite() || det == 0.0 {
            return None;
        }
        let inv = 1.0 / det;
        // Adjugate = transpose of the cofactor matrix.
        Some(Matrix3::new(
            a00 * inv, a10 * inv, a20 * inv,
            a01 * inv, a11 * inv, a21 * inv,
            a02 * inv, a12 * inv, a22 * inv,
        ))
    }

    /// Eigen-decomposition of a **symmetric** matrix via cyclic Jacobi.
    /// `eigenvectors`' columns are the (unit) eigenvectors; eigenvalues are
    /// unordered (matching `nalgebra::SymmetricEigen`).
    pub fn symmetric_eigen(&self) -> SymmetricEigen {
        let (evals, evecs) = jacobi_sym(&self.d, 3);
        SymmetricEigen {
            eigenvalues: Vector3::new(evals[0], evals[1], evals[2]),
            eigenvectors: Matrix3 {
                d: [
                    evecs[0], evecs[1], evecs[2],
                    evecs[3], evecs[4], evecs[5],
                    evecs[6], evecs[7], evecs[8],
                ],
                _p: PhantomData,
            },
        }
    }

    /// Singular value decomposition. Computed from the symmetric eigen of MᵀM:
    /// `M = U Σ Vᵀ`, with `V` the eigenvectors of MᵀM and `u_i = M v_i / σ_i`.
    /// The booleans mirror `nalgebra`'s API; both factors are always produced.
    pub fn svd(&self, _compute_u: bool, _compute_v: bool) -> Svd {
        let ata = self.transpose() * *self;
        let se = ata.symmetric_eigen();
        let mut sigma = [0.0f64; 3];
        for i in 0..3 {
            sigma[i] = se.eigenvalues[i].max(0.0).sqrt();
        }
        let vmat = se.eigenvectors;

        let mut u = Matrix3::zeros();
        let mut degenerate = [false; 3];
        for i in 0..3 {
            let vi = vmat.column(i);
            let mvi = *self * vi;
            if sigma[i] > 1e-12 {
                u.set_column(i, mvi / sigma[i]);
            } else {
                degenerate[i] = true;
            }
        }
        // Rebuild any degenerate (near-zero σ) columns to keep U orthonormal.
        // (Only reached for (near-)singular inputs; the stitcher feeds
        // near-rotation matrices, so σ ≈ 1 and this is a no-op there.)
        fixup_orthonormal(&mut u, &degenerate);

        Svd {
            u: Some(u),
            v_t: Some(vmat.transpose()),
            singular_values: Vector3::new(sigma[0], sigma[1], sigma[2]),
        }
    }
}

/// Fill columns flagged as degenerate with an orthonormal completion of the
/// remaining columns, so `U` stays a valid orthogonal matrix.
fn fixup_orthonormal(u: &mut Matrix3<f64>, degenerate: &[bool; 3]) {
    let n_bad = degenerate.iter().filter(|b| **b).count();
    if n_bad == 0 {
        return;
    }
    let good: Vec<usize> = (0..3).filter(|i| !degenerate[*i]).collect();
    let bad: Vec<usize> = (0..3).filter(|i| degenerate[*i]).collect();

    if n_bad == 1 && good.len() == 2 {
        // Third column = ± cross product of the two good ones.
        let c = u.column(good[0]).cross(&u.column(good[1]));
        let n = c.norm();
        u.set_column(bad[0], if n > 1e-12 { c / n } else { basis(bad[0]) });
    } else {
        // Two or three degenerate columns: fall back to filling with the
        // standard basis, then Gram-Schmidt against existing good columns.
        for &bi in &bad {
            let mut e = basis(bi);
            for &gi in &good {
                let g = u.column(gi);
                let proj = g.dot(&e);
                e = e - g * proj;
            }
            let n = e.norm();
            u.set_column(bi, if n > 1e-12 { e / n } else { basis(bi) });
        }
    }
}

fn basis(i: usize) -> Vector3<f64> {
    match i {
        0 => Vector3::new(1.0, 0.0, 0.0),
        1 => Vector3::new(0.0, 1.0, 0.0),
        _ => Vector3::new(0.0, 0.0, 1.0),
    }
}

/// Cyclic Jacobi eigen-decomposition of a symmetric `n×n` matrix given
/// row-major in `a_in`. Returns `(eigenvalues, eigenvectors)` where
/// `eigenvectors` is row-major `n×n` and column `i` is the unit eigenvector
/// for `eigenvalues[i]` (unordered). Used for both the 3×3 `symmetric_eigen`
/// and the `DMatrix` SVD (via AᵀA).
fn jacobi_sym(a_in: &[f64], n: usize) -> (Vec<f64>, Vec<f64>) {
    let mut a = a_in.to_vec();
    let mut v = vec![0.0f64; n * n];
    for i in 0..n {
        v[i * n + i] = 1.0;
    }
    let idx = |r: usize, c: usize| r * n + c;

    // n·n·(a few) sweeps is ample; 3×3 converges in ~5, 9×9 in well under 100.
    for _ in 0..100 {
        // Sum of squared strict-upper off-diagonals.
        let mut off = 0.0;
        for p in 0..n {
            for q in (p + 1)..n {
                off += a[idx(p, q)] * a[idx(p, q)];
            }
        }
        if off < 1e-300 {
            break;
        }
        for p in 0..n {
            for q in (p + 1)..n {
                let apq = a[idx(p, q)];
                if apq.abs() < 1e-300 {
                    continue;
                }
                let app = a[idx(p, p)];
                let aqq = a[idx(q, q)];
                let theta = (aqq - app) / (2.0 * apq);
                let t = if theta == 0.0 {
                    1.0
                } else {
                    theta.signum() / (theta.abs() + (theta * theta + 1.0).sqrt())
                };
                let cc = 1.0 / (t * t + 1.0).sqrt();
                let ss = t * cc;

                // B = A * G  (rotate columns p, q).
                for k in 0..n {
                    let akp = a[idx(k, p)];
                    let akq = a[idx(k, q)];
                    a[idx(k, p)] = cc * akp - ss * akq;
                    a[idx(k, q)] = ss * akp + cc * akq;
                }
                // A' = Gᵀ * B  (rotate rows p, q).
                for k in 0..n {
                    let bpk = a[idx(p, k)];
                    let bqk = a[idx(q, k)];
                    a[idx(p, k)] = cc * bpk - ss * bqk;
                    a[idx(q, k)] = ss * bpk + cc * bqk;
                }
                // Accumulate eigenvectors: V' = V * G.
                for k in 0..n {
                    let vkp = v[idx(k, p)];
                    let vkq = v[idx(k, q)];
                    v[idx(k, p)] = cc * vkp - ss * vkq;
                    v[idx(k, q)] = ss * vkp + cc * vkq;
                }
            }
        }
    }

    let evals: Vec<f64> = (0..n).map(|i| a[idx(i, i)]).collect();
    (evals, v)
}

impl Index<(usize, usize)> for Matrix3<f64> {
    type Output = f64;
    #[inline]
    fn index(&self, (r, c): (usize, usize)) -> &f64 {
        &self.d[r * 3 + c]
    }
}

impl IndexMut<(usize, usize)> for Matrix3<f64> {
    #[inline]
    fn index_mut(&mut self, (r, c): (usize, usize)) -> &mut f64 {
        &mut self.d[r * 3 + c]
    }
}

impl Mul<Matrix3<f64>> for Matrix3<f64> {
    type Output = Matrix3<f64>;
    fn mul(self, rhs: Matrix3<f64>) -> Matrix3<f64> {
        let mut out = [0.0f64; 9];
        for r in 0..3 {
            for c in 0..3 {
                let mut s = 0.0;
                for k in 0..3 {
                    s += self.at(r, k) * rhs.at(k, c);
                }
                out[r * 3 + c] = s;
            }
        }
        Matrix3 { d: out, _p: PhantomData }
    }
}

impl Mul<Vector3<f64>> for Matrix3<f64> {
    type Output = Vector3<f64>;
    fn mul(self, v: Vector3<f64>) -> Vector3<f64> {
        Vector3::new(
            self.d[0] * v.x + self.d[1] * v.y + self.d[2] * v.z,
            self.d[3] * v.x + self.d[4] * v.y + self.d[5] * v.z,
            self.d[6] * v.x + self.d[7] * v.y + self.d[8] * v.z,
        )
    }
}

// Reference operand variants (nalgebra implements these too). `Matrix3`/`Vector3`
// are `Copy`, so each just dereferences and delegates to the owned impls.
impl Mul<&Matrix3<f64>> for Matrix3<f64> {
    type Output = Matrix3<f64>;
    fn mul(self, rhs: &Matrix3<f64>) -> Matrix3<f64> {
        self * *rhs
    }
}

impl Mul<Matrix3<f64>> for &Matrix3<f64> {
    type Output = Matrix3<f64>;
    fn mul(self, rhs: Matrix3<f64>) -> Matrix3<f64> {
        *self * rhs
    }
}

impl Mul<&Matrix3<f64>> for &Matrix3<f64> {
    type Output = Matrix3<f64>;
    fn mul(self, rhs: &Matrix3<f64>) -> Matrix3<f64> {
        *self * *rhs
    }
}

impl Mul<Vector3<f64>> for &Matrix3<f64> {
    type Output = Vector3<f64>;
    fn mul(self, v: Vector3<f64>) -> Vector3<f64> {
        *self * v
    }
}

impl AddAssign<Matrix3<f64>> for Matrix3<f64> {
    fn add_assign(&mut self, rhs: Matrix3<f64>) {
        for i in 0..9 {
            self.d[i] += rhs.d[i];
        }
    }
}

impl DivAssign<f64> for Matrix3<f64> {
    fn div_assign(&mut self, s: f64) {
        for i in 0..9 {
            self.d[i] /= s;
        }
    }
}

// ===========================================================================
// Vector3 / RowVector3
// ===========================================================================

#[derive(Clone, Copy, Debug)]
pub struct Vector3<T = f64> {
    pub x: f64,
    pub y: f64,
    pub z: f64,
    _p: PhantomData<T>,
}

impl Vector3<f64> {
    pub fn new(x: f64, y: f64, z: f64) -> Self {
        Vector3 { x, y, z, _p: PhantomData }
    }

    pub fn zeros() -> Self {
        Vector3::new(0.0, 0.0, 0.0)
    }

    /// Present for parity with `nalgebra` column-view `.into_owned()`; our
    /// `Matrix3::column` already returns an owned vector, so this is identity.
    pub fn into_owned(self) -> Self {
        self
    }

    pub fn norm(&self) -> f64 {
        (self.x * self.x + self.y * self.y + self.z * self.z).sqrt()
    }

    pub fn dot(&self, o: &Vector3<f64>) -> f64 {
        self.x * o.x + self.y * o.y + self.z * o.z
    }

    pub fn cross(&self, o: &Vector3<f64>) -> Vector3<f64> {
        Vector3::new(
            self.y * o.z - self.z * o.y,
            self.z * o.x - self.x * o.z,
            self.x * o.y - self.y * o.x,
        )
    }

    pub fn transpose(&self) -> RowVector3<f64> {
        RowVector3 { d: [self.x, self.y, self.z], _p: PhantomData }
    }
}

impl Index<usize> for Vector3<f64> {
    type Output = f64;
    #[inline]
    fn index(&self, i: usize) -> &f64 {
        match i {
            0 => &self.x,
            1 => &self.y,
            2 => &self.z,
            _ => panic!("Vector3 index out of range: {i}"),
        }
    }
}

impl IndexMut<usize> for Vector3<f64> {
    #[inline]
    fn index_mut(&mut self, i: usize) -> &mut f64 {
        match i {
            0 => &mut self.x,
            1 => &mut self.y,
            2 => &mut self.z,
            _ => panic!("Vector3 index out of range: {i}"),
        }
    }
}

impl Div<f64> for Vector3<f64> {
    type Output = Vector3<f64>;
    fn div(self, s: f64) -> Vector3<f64> {
        Vector3::new(self.x / s, self.y / s, self.z / s)
    }
}

impl DivAssign<f64> for Vector3<f64> {
    fn div_assign(&mut self, s: f64) {
        self.x /= s;
        self.y /= s;
        self.z /= s;
    }
}

impl Mul<f64> for Vector3<f64> {
    type Output = Vector3<f64>;
    fn mul(self, s: f64) -> Vector3<f64> {
        Vector3::new(self.x * s, self.y * s, self.z * s)
    }
}

impl std::ops::Sub<Vector3<f64>> for Vector3<f64> {
    type Output = Vector3<f64>;
    fn sub(self, o: Vector3<f64>) -> Vector3<f64> {
        Vector3::new(self.x - o.x, self.y - o.y, self.z - o.z)
    }
}

impl Neg for Vector3<f64> {
    type Output = Vector3<f64>;
    fn neg(self) -> Vector3<f64> {
        Vector3::new(-self.x, -self.y, -self.z)
    }
}

impl AddAssign<Vector3<f64>> for Vector3<f64> {
    fn add_assign(&mut self, o: Vector3<f64>) {
        self.x += o.x;
        self.y += o.y;
        self.z += o.z;
    }
}

/// Outer product `v * vᵀ` → 3×3 matrix.
impl Mul<RowVector3<f64>> for Vector3<f64> {
    type Output = Matrix3<f64>;
    fn mul(self, row: RowVector3<f64>) -> Matrix3<f64> {
        let (a, b, c) = (self.x, self.y, self.z);
        let (p, q, r) = (row.d[0], row.d[1], row.d[2]);
        Matrix3::new(a * p, a * q, a * r, b * p, b * q, b * r, c * p, c * q, c * r)
    }
}

#[derive(Clone, Copy, Debug)]
pub struct RowVector3<T = f64> {
    d: [f64; 3],
    _p: PhantomData<T>,
}

// ===========================================================================
// Return structs for svd / symmetric_eigen
// ===========================================================================

pub struct Svd {
    pub u: Option<Matrix3<f64>>,
    pub v_t: Option<Matrix3<f64>>,
    #[allow(dead_code)]
    pub singular_values: Vector3<f64>,
}

pub struct SymmetricEigen {
    pub eigenvalues: Vector3<f64>,
    pub eigenvectors: Matrix3<f64>,
}

/// Result of `DMatrix::svd`. Only `v_t` is populated (see `DMatrix::svd`).
pub struct DSvd {
    #[allow(dead_code)]
    pub u: Option<DMatrix<f64>>,
    pub v_t: Option<DMatrix<f64>>,
}

// ===========================================================================
// DMatrix / DVector (dense, dynamic) + partial-pivot LU solve
// ===========================================================================

#[derive(Clone, Debug)]
pub struct DMatrix<T = f64> {
    rows: usize,
    cols: usize,
    d: Vec<f64>,
    _p: PhantomData<T>,
}

impl DMatrix<f64> {
    pub fn zeros(rows: usize, cols: usize) -> Self {
        DMatrix { rows, cols, d: vec![0.0; rows * cols], _p: PhantomData }
    }

    pub fn nrows(&self) -> usize {
        self.rows
    }

    /// Row `r` as an owned `Vec<f64>` (indexable like a `nalgebra` row view).
    pub fn row(&self, r: usize) -> Vec<f64> {
        self.d[r * self.cols..(r + 1) * self.cols].to_vec()
    }

    /// Thin SVD sufficient for the DLT null-space solve: only `v_t` is produced,
    /// with rows ordered by **descending** singular value (so the last row is
    /// the smallest singular vector — the homography null space). Computed from
    /// the symmetric eigen of `AᵀA`; the DLT points are Hartley-normalized
    /// beforehand, so `AᵀA` is well-conditioned. `u` is not needed by any caller.
    pub fn svd(&self, _compute_u: bool, _compute_v: bool) -> DSvd {
        let (m, n) = (self.rows, self.cols);
        // AᵀA (n×n, symmetric).
        let mut ata = vec![0.0f64; n * n];
        for i in 0..n {
            for j in i..n {
                let mut s = 0.0;
                for k in 0..m {
                    s += self.d[k * n + i] * self.d[k * n + j];
                }
                ata[i * n + j] = s;
                ata[j * n + i] = s;
            }
        }
        let (evals, evecs) = jacobi_sym(&ata, n);
        // Order eigenpairs by descending eigenvalue (== descending singular value).
        let mut order: Vec<usize> = (0..n).collect();
        order.sort_by(|&a, &b| evals[b].total_cmp(&evals[a]));

        // v_t: row `rank` = eigenvector for the rank-th largest eigenvalue.
        let mut vt = DMatrix::zeros(n, n);
        for (rank, &ei) in order.iter().enumerate() {
            for comp in 0..n {
                vt.d[rank * n + comp] = evecs[comp * n + ei];
            }
        }
        DSvd { u: None, v_t: Some(vt) }
    }

    /// Partial-pivot LU factorization (square matrices).
    pub fn lu(&self) -> Lu {
        let n = self.rows;
        debug_assert_eq!(self.rows, self.cols, "lu() requires a square matrix");
        let mut a = self.d.clone();
        let mut piv: Vec<usize> = (0..n).collect();
        let mut singular = false;

        for k in 0..n {
            // Choose the largest-magnitude pivot in column k.
            let mut p = k;
            let mut max = a[k * n + k].abs();
            for i in (k + 1)..n {
                let v = a[i * n + k].abs();
                if v > max {
                    max = v;
                    p = i;
                }
            }
            if max == 0.0 {
                singular = true;
                break;
            }
            if p != k {
                for j in 0..n {
                    a.swap(p * n + j, k * n + j);
                }
                piv.swap(p, k);
            }
            let akk = a[k * n + k];
            for i in (k + 1)..n {
                let f = a[i * n + k] / akk;
                a[i * n + k] = f;
                for j in (k + 1)..n {
                    a[i * n + j] -= f * a[k * n + j];
                }
            }
        }

        Lu { lu: a, n, piv, singular }
    }
}

impl Index<(usize, usize)> for DMatrix<f64> {
    type Output = f64;
    #[inline]
    fn index(&self, (r, c): (usize, usize)) -> &f64 {
        &self.d[r * self.cols + c]
    }
}

impl IndexMut<(usize, usize)> for DMatrix<f64> {
    #[inline]
    fn index_mut(&mut self, (r, c): (usize, usize)) -> &mut f64 {
        &mut self.d[r * self.cols + c]
    }
}

impl AddAssign<DMatrix<f64>> for DMatrix<f64> {
    fn add_assign(&mut self, rhs: DMatrix<f64>) {
        debug_assert_eq!(self.d.len(), rhs.d.len());
        for i in 0..self.d.len() {
            self.d[i] += rhs.d[i];
        }
    }
}

#[derive(Clone, Debug)]
pub struct DVector<T = f64> {
    d: Vec<f64>,
    _p: PhantomData<T>,
}

impl DVector<f64> {
    pub fn zeros(n: usize) -> Self {
        DVector { d: vec![0.0; n], _p: PhantomData }
    }
}

impl Index<usize> for DVector<f64> {
    type Output = f64;
    #[inline]
    fn index(&self, i: usize) -> &f64 {
        &self.d[i]
    }
}

impl IndexMut<usize> for DVector<f64> {
    #[inline]
    fn index_mut(&mut self, i: usize) -> &mut f64 {
        &mut self.d[i]
    }
}

impl AddAssign<DVector<f64>> for DVector<f64> {
    fn add_assign(&mut self, rhs: DVector<f64>) {
        debug_assert_eq!(self.d.len(), rhs.d.len());
        for i in 0..self.d.len() {
            self.d[i] += rhs.d[i];
        }
    }
}

impl Neg for &DVector<f64> {
    type Output = DVector<f64>;
    fn neg(self) -> DVector<f64> {
        DVector { d: self.d.iter().map(|v| -v).collect(), _p: PhantomData }
    }
}

/// LU factors with the row permutation, ready to solve `A x = b`.
pub struct Lu {
    lu: Vec<f64>, // combined L (unit diag, below) and U (on/above diag), n×n
    n: usize,
    piv: Vec<usize>,
    singular: bool,
}

impl Lu {
    /// Solve `A x = b`; `None` if the matrix was singular.
    pub fn solve(&self, b: &DVector<f64>) -> Option<DVector<f64>> {
        if self.singular {
            return None;
        }
        let n = self.n;
        // Apply the row permutation to the RHS.
        let mut x = vec![0.0f64; n];
        for i in 0..n {
            x[i] = b.d[self.piv[i]];
        }
        // Forward substitution (unit-lower L).
        for i in 0..n {
            let mut s = x[i];
            for j in 0..i {
                s -= self.lu[i * n + j] * x[j];
            }
            x[i] = s;
        }
        // Back substitution (upper U).
        for i in (0..n).rev() {
            let mut s = x[i];
            for j in (i + 1)..n {
                s -= self.lu[i * n + j] * x[j];
            }
            let d = self.lu[i * n + i];
            if d == 0.0 {
                return None;
            }
            x[i] = s / d;
        }
        Some(DVector { d: x, _p: PhantomData })
    }
}

// ===========================================================================
// Tests
// ===========================================================================

#[cfg(test)]
mod tests {
    use super::*;

    fn approx(a: f64, b: f64, tol: f64) -> bool {
        (a - b).abs() <= tol
    }

    #[test]
    fn inverse_roundtrip() {
        let m = Matrix3::new(2.0, 0.0, 1.0, 1.0, 3.0, 2.0, 1.0, 0.0, 4.0);
        let inv = m.try_inverse().unwrap();
        let id = m * inv;
        for r in 0..3 {
            for c in 0..3 {
                let want = if r == c { 1.0 } else { 0.0 };
                assert!(approx(id[(r, c)], want, 1e-12), "id[{r}{c}]={}", id[(r, c)]);
            }
        }
    }

    #[test]
    fn singular_inverse_is_none() {
        let m = Matrix3::new(1.0, 2.0, 3.0, 2.0, 4.0, 6.0, 1.0, 1.0, 1.0);
        assert!(m.try_inverse().is_none());
    }

    #[test]
    fn orthonormalize_preserves_rotation() {
        // A pure rotation about z by 0.3 rad; orthonormalize (U Vᵀ) must return it.
        let (s, c) = 0.3_f64.sin_cos();
        let r = Matrix3::new(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0);
        let svd = r.svd(true, true);
        let q = svd.u.unwrap() * svd.v_t.unwrap();
        for i in 0..3 {
            for j in 0..3 {
                assert!(approx(q[(i, j)], r[(i, j)], 1e-9), "q[{i}{j}]={}", q[(i, j)]);
            }
        }
    }

    #[test]
    fn svd_reconstructs_matrix() {
        let m = Matrix3::new(1.0, 2.0, 0.5, 0.3, 1.5, -1.0, 2.0, 0.1, 3.0);
        let svd = m.svd(true, true);
        let u = svd.u.unwrap();
        let vt = svd.v_t.unwrap();
        let s = svd.singular_values;
        let sig = Matrix3::new(s.x, 0.0, 0.0, 0.0, s.y, 0.0, 0.0, 0.0, s.z);
        let recon = u * sig * vt;
        for i in 0..3 {
            for j in 0..3 {
                assert!(approx(recon[(i, j)], m[(i, j)], 1e-9), "recon[{i}{j}]={}", recon[(i, j)]);
            }
        }
    }

    #[test]
    fn symmetric_eigen_diagonalizes() {
        let m = Matrix3::new(2.0, 1.0, 0.0, 1.0, 2.0, 0.0, 0.0, 0.0, 5.0);
        let se = m.symmetric_eigen();
        // Eigenvalues of [[2,1],[1,2]] are 1 and 3, plus 5.
        let mut ev = [se.eigenvalues[0], se.eigenvalues[1], se.eigenvalues[2]];
        ev.sort_by(|a, b| a.total_cmp(b));
        assert!(approx(ev[0], 1.0, 1e-9));
        assert!(approx(ev[1], 3.0, 1e-9));
        assert!(approx(ev[2], 5.0, 1e-9));
        // Reconstruct: V Λ Vᵀ == M.
        let v = se.eigenvectors;
        let l = Matrix3::new(
            se.eigenvalues[0], 0.0, 0.0,
            0.0, se.eigenvalues[1], 0.0,
            0.0, 0.0, se.eigenvalues[2],
        );
        let recon = v * l * v.transpose();
        for i in 0..3 {
            for j in 0..3 {
                assert!(approx(recon[(i, j)], m[(i, j)], 1e-9));
            }
        }
    }

    #[test]
    fn lu_solves_system() {
        // 3×3 system with known solution x = [1, 2, 3].
        let mut a = DMatrix::zeros(3, 3);
        let vals = [2.0, 1.0, 1.0, 1.0, 3.0, 2.0, 1.0, 0.0, 4.0];
        for r in 0..3 {
            for c in 0..3 {
                a[(r, c)] = vals[r * 3 + c];
            }
        }
        let mut b = DVector::zeros(3);
        // b = A * [1,2,3]
        for r in 0..3 {
            let mut s = 0.0;
            for c in 0..3 {
                s += a[(r, c)] * (c as f64 + 1.0);
            }
            b[r] = s;
        }
        let x = a.lu().solve(&b).unwrap();
        assert!(approx(x[0], 1.0, 1e-12));
        assert!(approx(x[1], 2.0, 1e-12));
        assert!(approx(x[2], 3.0, 1e-12));
    }

    #[test]
    fn lu_singular_is_none() {
        let mut a = DMatrix::zeros(2, 2);
        a[(0, 0)] = 1.0;
        a[(0, 1)] = 2.0;
        a[(1, 0)] = 2.0;
        a[(1, 1)] = 4.0;
        let b = DVector::zeros(2);
        assert!(a.lu().solve(&b).is_none());
    }
}
