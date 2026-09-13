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

// Submodules, split by area so each file stays reviewable. Re-exported below so
// existing `crate::linalg::{Matrix3, Vector3, …}` paths keep working unchanged.
mod dense;
mod jacobi;
mod matrix3;
mod vector;

pub use dense::{DMatrix, DSvd, DVector, Lu};
pub use matrix3::{Matrix3, Svd, SymmetricEigen};
pub use vector::{RowVector3, Vector3};

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
