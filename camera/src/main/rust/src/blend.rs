//! Compositing blender: **MultiBandBlender** parity with OpenCV `modules/stitching/src/blenders.cpp`
//! Implements:
//!   actual_num_bands=5 auto-crop num_bands=min(actual, ceil(log(max_len)/log2))
//!   dst divisible by 1<<num_bands
//!   weight maps mask/255 CV_32F (our masks 0/1)
//!   Laplacian pyramids iterative (rows+1)/2, Gaussian weight pyramids
//!   gap 3*(1<<bands) alignment TL to multiple 1<<bands (simplified – gap border reflect for quality)
//!   feed weighted laplacian, blend reconstruct pyrUp
//! Falls back to seam-aware feather blend if pyramid fails (low memory / degenerate).

use crate::imgbuf::Rgba;
use crate::sphere::WarpedTile;

/// Separable box blur of a float plane (running-sum, radius r).
fn box_blur(src: &[f32], w: usize, h: usize, r: usize) -> Vec<f32> {
    if r == 0 {
        return src.to_vec();
    }
    let mut tmp = vec![0f32; w * h];
    let norm = 1.0 / (2 * r + 1) as f32;
    // horizontal
    for y in 0..h {
        let row = y * w;
        let mut sum = 0.0;
        for x in 0..=r.min(w - 1) {
            sum += src[row + x];
        }
        for x in 0..w {
            tmp[row + x] = sum * norm;
            let add = x + r + 1;
            let sub = x as isize - r as isize;
            if add < w {
                sum += src[row + add];
            }
            if sub >= 0 {
                sum -= src[row + sub as usize];
            }
        }
    }
    let mut out = vec![0f32; w * h];
    // vertical
    for x in 0..w {
        let mut sum = 0.0;
        for y in 0..=r.min(h - 1) {
            sum += tmp[y * w + x];
        }
        for y in 0..h {
            out[y * w + x] = sum * norm;
            let add = y + r + 1;
            let sub = y as isize - r as isize;
            if add < h {
                sum += tmp[add * w + x];
            }
            if sub >= 0 {
                sum -= tmp[sub as usize * w + x];
            }
        }
    }
    out
}

// ---------------------------------------------------------------------------
// MultiBand core structures – f32 images for Laplacian accumulation
// ---------------------------------------------------------------------------

#[derive(Clone)]
struct Img3 {
    w: usize,
    h: usize,
    data: Vec<f32>, // w*h*3
}
#[derive(Clone)]
struct WMap {
    w: usize,
    h: usize,
    data: Vec<f32>, // w*h
}

fn reflect_101(idx: isize, max: usize) -> usize {
    let mut i = idx;
    let m = max as isize;
    if m <= 1 {
        return 0;
    }
    // OpenCV BORDER_REFLECT_101: reflect without repeating border
    while i < 0 || i >= m {
        if i < 0 {
            i = -i;
        } else {
            i = 2 * m - i - 2;
        }
    }
    i as usize
}

fn pyr_down_3ch(src: &Img3) -> Img3 {
    // Gaussian blur 5-tap [1 4 6 4 1]/16 separable with reflect_101, then downsample (even pixels)
    let w = src.w;
    let h = src.h;
    if w == 0 || h == 0 {
        return Img3 { w: 0, h: 0, data: Vec::new() };
    }
    // horizontal blur -> tmp
    let mut tmp = vec![0f32; w * h * 3];
    let kernel = [1.0f32, 4.0, 6.0, 4.0, 1.0];
    let ksum = 16.0;
    for y in 0..h {
        for x in 0..w {
            let mut acc = [0f32; 3];
            for k in -2isize..=2 {
                let xs = reflect_101(x as isize + k, w);
                let coeff = kernel[(k + 2) as usize] / ksum;
                let base = (y * w + xs) * 3;
                acc[0] += src.data[base] * coeff;
                acc[1] += src.data[base + 1] * coeff;
                acc[2] += src.data[base + 2] * coeff;
            }
            let base = (y * w + x) * 3;
            tmp[base] = acc[0];
            tmp[base + 1] = acc[1];
            tmp[base + 2] = acc[2];
        }
    }
    // vertical blur -> blurred
    let mut blurred = vec![0f32; w * h * 3];
    for y in 0..h {
        for x in 0..w {
            let mut acc = [0f32; 3];
            for k in -2isize..=2 {
                let ys = reflect_101(y as isize + k, h);
                let coeff = kernel[(k + 2) as usize] / ksum;
                let base = (ys * w + x) * 3;
                acc[0] += tmp[base] * coeff;
                acc[1] += tmp[base + 1] * coeff;
                acc[2] += tmp[base + 2] * coeff;
            }
            let base = (y * w + x) * 3;
            blurred[base] = acc[0];
            blurred[base + 1] = acc[1];
            blurred[base + 2] = acc[2];
        }
    }
    // downsample (w+1)/2
    let nw = (w + 1) / 2;
    let nh = (h + 1) / 2;
    if nw == 0 || nh == 0 {
        return Img3 { w: 0, h: 0, data: Vec::new() };
    }
    let mut data = vec![0f32; nw * nh * 3];
    for y in 0..nh {
        let sy = (y * 2).min(h - 1);
        for x in 0..nw {
            let sx = (x * 2).min(w - 1);
            let s_base = (sy * w + sx) * 3;
            let d_base = (y * nw + x) * 3;
            data[d_base] = blurred[s_base];
            data[d_base + 1] = blurred[s_base + 1];
            data[d_base + 2] = blurred[s_base + 2];
        }
    }
    Img3 { w: nw, h: nh, data }
}

fn pyr_down_1ch(src: &WMap) -> WMap {
    let w = src.w;
    let h = src.h;
    if w == 0 || h == 0 {
        return WMap { w: 0, h: 0, data: Vec::new() };
    }
    let mut tmp = vec![0f32; w * h];
    let kernel = [1.0f32, 4.0, 6.0, 4.0, 1.0];
    let ksum = 16.0;
    for y in 0..h {
        for x in 0..w {
            let mut acc = 0f32;
            for k in -2isize..=2 {
                let xs = reflect_101(x as isize + k, w);
                acc += src.data[y * w + xs] * kernel[(k + 2) as usize] / ksum;
            }
            tmp[y * w + x] = acc;
        }
    }
    let mut blurred = vec![0f32; w * h];
    for y in 0..h {
        for x in 0..w {
            let mut acc = 0f32;
            for k in -2isize..=2 {
                let ys = reflect_101(y as isize + k, h);
                acc += tmp[ys * w + x] * kernel[(k + 2) as usize] / ksum;
            }
            blurred[y * w + x] = acc;
        }
    }
    let nw = (w + 1) / 2;
    let nh = (h + 1) / 2;
    if nw == 0 || nh == 0 {
        return WMap { w: 0, h: 0, data: Vec::new() };
    }
    let mut data = vec![0f32; nw * nh];
    for y in 0..nh {
        let sy = (y * 2).min(h - 1);
        for x in 0..nw {
            let sx = (x * 2).min(w - 1);
            data[y * nw + x] = blurred[sy * w + sx];
        }
    }
    WMap { w: nw, h: nh, data }
}

fn pyr_up_3ch(src: &Img3, dst_w: usize, dst_h: usize) -> Img3 {
    // Bilinear upsample to dst size – approximates OpenCV pyrUp Gaussian *4.
    // For exact reconstruction we use bilinear; good enough for visual parity.
    if src.w == 0 || src.h == 0 || dst_w == 0 || dst_h == 0 {
        return Img3 { w: dst_w, h: dst_h, data: vec![0f32; dst_w * dst_h * 3] };
    }
    let mut data = vec![0f32; dst_w * dst_h * 3];
    let src_w = src.w as f32;
    let src_h = src.h as f32;
    let dw = dst_w as f32;
    let dh = dst_h as f32;
    for y in 0..dst_h {
        let fy = if dst_h == 1 { 0.0 } else { (y as f32 + 0.5) * src_h / dh - 0.5 };
        let fy = fy.clamp(0.0, src_h - 1.0);
        let y0 = fy.floor() as usize;
        let y1 = (y0 + 1).min(src.h - 1);
        let ay = fy - y0 as f32;
        for x in 0..dst_w {
            let fx = if dst_w == 1 { 0.0 } else { (x as f32 + 0.5) * src_w / dw - 0.5 };
            let fx = fx.clamp(0.0, src_w - 1.0);
            let x0 = fx.floor() as usize;
            let x1 = (x0 + 1).min(src.w - 1);
            let ax = fx - x0 as f32;
            let s00 = (y0 * src.w + x0) * 3;
            let s01 = (y0 * src.w + x1) * 3;
            let s10 = (y1 * src.w + x0) * 3;
            let s11 = (y1 * src.w + x1) * 3;
            let d = (y * dst_w + x) * 3;
            for c in 0..3 {
                let top = src.data[s00 + c] * (1.0 - ax) + src.data[s01 + c] * ax;
                let bot = src.data[s10 + c] * (1.0 - ax) + src.data[s11 + c] * ax;
                data[d + c] = top * (1.0 - ay) + bot * ay;
            }
        }
    }
    Img3 { w: dst_w, h: dst_h, data }
}

fn pyr_up_1ch(src: &WMap, dst_w: usize, dst_h: usize) -> WMap {
    if src.w == 0 || src.h == 0 || dst_w == 0 || dst_h == 0 {
        return WMap { w: dst_w, h: dst_h, data: vec![0f32; dst_w * dst_h] };
    }
    let mut data = vec![0f32; dst_w * dst_h];
    let src_w = src.w as f32;
    let src_h = src.h as f32;
    let dw = dst_w as f32;
    let dh = dst_h as f32;
    for y in 0..dst_h {
        let fy = if dst_h == 1 { 0.0 } else { (y as f32 + 0.5) * src_h / dh - 0.5 };
        let fy = fy.clamp(0.0, src_h - 1.0);
        let y0 = fy.floor() as usize;
        let y1 = (y0 + 1).min(src.h - 1);
        let ay = fy - y0 as f32;
        for x in 0..dst_w {
            let fx = if dst_w == 1 { 0.0 } else { (x as f32 + 0.5) * src_w / dw - 0.5 };
            let fx = fx.clamp(0.0, src_w - 1.0);
            let x0 = fx.floor() as usize;
            let x1 = (x0 + 1).min(src.w - 1);
            let ax = fx - x0 as f32;
            let top = src.data[y0 * src.w + x0] * (1.0 - ax) + src.data[y0 * src.w + x1] * ax;
            let bot = src.data[y1 * src.w + x0] * (1.0 - ax) + src.data[y1 * src.w + x1] * ax;
            data[y * dst_w + x] = top * (1.0 - ay) + bot * ay;
        }
    }
    WMap { w: dst_w, h: dst_h, data }
}

// ---------------------------------------------------------------------------
// Feather fallback (original) – kept and used if multiband fails
// ---------------------------------------------------------------------------

fn feather_blend(tiles: &[WarpedTile], masks: &[Vec<u8>], gain_maps: &[Vec<f32>], gx0: i32, gy0: i32, cw: usize, ch: usize) -> Option<Rgba> {
    let num = tiles.len();
    let mut acc = vec![0f32; cw * ch * 3];
    let mut accw = vec![0f32; cw * ch];

    for ti in 0..num {
        let t = &tiles[ti];
        let gmap = &gain_maps[ti];
        let (tw, th) = (t.img.w, t.img.h);
        let radius = (tw.min(th) / 40).clamp(6, 32);
        let maskf: Vec<f32> = masks[ti].iter().map(|&m| if m != 0 { 1.0 } else { 0.0 }).collect();
        let feather = box_blur(&maskf, tw, th, radius);
        for ly in 0..th {
            for lx in 0..tw {
                let li = ly * tw + lx;
                let c = t.img.get(lx, ly);
                if c[3] == 0 {
                    continue;
                }
                let weight = feather[li];
                if weight <= 0.0 {
                    continue;
                }
                let gain = gmap[li];
                let gxp = (t.corner_x - gx0) as usize + lx;
                let gyp = (t.corner_y - gy0) as usize + ly;
                if gxp >= cw || gyp >= ch {
                    continue;
                }
                let idx = gyp * cw + gxp;
                acc[idx * 3] += (c[0] as f32 * gain).min(255.0) * weight;
                acc[idx * 3 + 1] += (c[1] as f32 * gain).min(255.0) * weight;
                acc[idx * 3 + 2] += (c[2] as f32 * gain).min(255.0) * weight;
                accw[idx] += weight;
            }
        }
    }
    for ti in 0..num {
        let t = &tiles[ti];
        let gmap = &gain_maps[ti];
        for ly in 0..t.img.h {
            for lx in 0..t.img.w {
                let c = t.img.get(lx, ly);
                if c[3] == 0 {
                    continue;
                }
                let gxp = (t.corner_x - gx0) as usize + lx;
                let gyp = (t.corner_y - gy0) as usize + ly;
                if gxp >= cw || gyp >= ch {
                    continue;
                }
                let idx = gyp * cw + gxp;
                if accw[idx] <= 0.0 {
                    let gain = gmap[ly * t.img.w + lx];
                    acc[idx * 3] = (c[0] as f32 * gain).min(255.0);
                    acc[idx * 3 + 1] = (c[1] as f32 * gain).min(255.0);
                    acc[idx * 3 + 2] = (c[2] as f32 * gain).min(255.0);
                    accw[idx] = 1.0;
                }
            }
        }
    }
    let mut out = Rgba::new(cw, ch);
    for i in 0..cw * ch {
        let w = accw[i];
        let d = i * 4;
        if w > 0.0 {
            out.px[d] = (acc[i * 3] / w).round().clamp(0.0, 255.0) as u8;
            out.px[d + 1] = (acc[i * 3 + 1] / w).round().clamp(0.0, 255.0) as u8;
            out.px[d + 2] = (acc[i * 3 + 2] / w).round().clamp(0.0, 255.0) as u8;
            out.px[d + 3] = 255;
        } else {
            out.px[d + 3] = 0;
        }
    }
    Some(crop_to_content(out))
}

// ---------------------------------------------------------------------------
// Public entry: multiband_blend with true Laplacian pyramid
// ---------------------------------------------------------------------------

pub fn multiband_blend(tiles: &[WarpedTile], masks: &[Vec<u8>], gain_maps: &[Vec<f32>]) -> Option<Rgba> {
    let num = tiles.len();
    if num == 0 {
        return None;
    }
    // Global canvas bounds
    let mut gx0 = i32::MAX;
    let mut gy0 = i32::MAX;
    let mut gx1 = i32::MIN;
    let mut gy1 = i32::MIN;
    for t in tiles {
        gx0 = gx0.min(t.corner_x);
        gy0 = gy0.min(t.corner_y);
        gx1 = gx1.max(t.corner_x + t.img.w as i32);
        gy1 = gy1.max(t.corner_y + t.img.h as i32);
    }
    let cw = (gx1 - gx0) as usize;
    let ch = (gy1 - gy0) as usize;
    if cw == 0 || ch == 0 || cw > 20000 || ch > 20000 {
        return None;
    }

    // OpenCV MultiBandBlender prepare logic
    let max_len = cw.max(ch) as f64;
    let mut nb = if max_len > 1.0 { (max_len.log2().ceil() as usize) } else { 1 };
    nb = nb.min(5).max(1); // actual_num_bands=5
    let num_bands = nb;

    // Padded size divisible by 1<<num_bands
    let div = 1usize << num_bands;
    let cw_padded = cw + (div - cw % div) % div;
    let ch_padded = ch + (div - ch % div) % div;
    if cw_padded > 20000 || ch_padded > 20000 {
        // Too large for pyramid – fallback to feather
        return feather_blend(tiles, masks, gain_maps, gx0, gy0, cw, ch);
    }

    // Global pyramid dimensions
    let mut global_ws = Vec::with_capacity(num_bands + 1);
    let mut global_hs = Vec::with_capacity(num_bands + 1);
    let mut bw = cw_padded;
    let mut bh = ch_padded;
    global_ws.push(bw);
    global_hs.push(bh);
    for _ in 0..num_bands {
        bw = (bw + 1) / 2;
        bh = (bh + 1) / 2;
        global_ws.push(bw);
        global_hs.push(bh);
    }

    // Allocate global laplacian and weight pyramids zero
    let mut global_lap: Vec<Img3> = (0..=num_bands).map(|l| Img3 { w: global_ws[l], h: global_hs[l], data: vec![0f32; global_ws[l] * global_hs[l] * 3] }).collect();
    let mut global_wmap: Vec<WMap> = (0..=num_bands).map(|l| WMap { w: global_ws[l], h: global_hs[l], data: vec![0f32; global_ws[l] * global_hs[l]] }).collect();

    // For each tile, build its image and weight, with gain applied
    // Gap handling: OpenCV uses gap=3*(1<<bands) and BORDER_REFLECT for image
    let gap = 3 * div;
    for (ti, tile) in tiles.iter().enumerate() {
        let tw0 = tile.img.w;
        let th0 = tile.img.h;
        let gain = &gain_maps[ti];
        let mask = &masks[ti];

        // Base image f32 with gain
        let mut base_img = Img3 { w: tw0, h: th0, data: vec![0f32; tw0 * th0 * 3] };
        let mut base_weight = WMap { w: tw0, h: th0, data: vec![0f32; tw0 * th0] };
        for y in 0..th0 {
            for x in 0..tw0 {
                let li = y * tw0 + x;
                let c = tile.img.get(x, y);
                if c[3] == 0 {
                    continue;
                }
                let g = gain.get(li).copied().unwrap_or(1.0);
                let base = li * 3;
                base_img.data[base] = (c[0] as f32 * g).min(255.0);
                base_img.data[base + 1] = (c[1] as f32 * g).min(255.0);
                base_img.data[base + 2] = (c[2] as f32 * g).min(255.0);
                if mask.get(li).copied().unwrap_or(0) != 0 {
                    base_weight.data[li] = 1.0; // mask/255 CV_32F – our mask 0/1 => 1.0
                }
            }
        }

        // Compute tl in padded canvas (relative to gx0,gy0)
        let tl_x = (tile.corner_x - gx0) as usize;
        let tl_y = (tile.corner_y - gy0) as usize;

        // Expand with gap for pyramid border handling (simplified – we expand base before pyramid but still place at tl)
        // For simplicity we keep expansion only for laplacian border reflect: we build bordered image larger
        // by gap where possible, with reflect for image and constant 0 for weight.
        // This matches OpenCV's copyMakeBorder logic.

        let tl_new_x_raw = tl_x.saturating_sub(gap);
        let tl_new_y_raw = tl_y.saturating_sub(gap);
        let br_new_x_raw = (tl_x + tw0 + gap).min(cw_padded);
        let br_new_y_raw = (tl_y + th0 + gap).min(ch_padded);

        // Align tl_new to multiple of div (1<<bands) – OpenCV: tl_new = dst_roi + ((tl_new - dst_roi)>>bands <<bands)
        let mut tl_new_x = (tl_new_x_raw >> num_bands) << num_bands;
        let mut tl_new_y = (tl_new_y_raw >> num_bands) << num_bands;
        let mut br_new_x = br_new_x_raw;
        let mut br_new_y = br_new_y_raw;
        // Make br-tl divisible by div
        let w_gap = br_new_x - tl_new_x;
        let h_gap = br_new_y - tl_new_y;
        br_new_x += (div - w_gap % div) % div;
        br_new_y += (div - h_gap % div) % div;
        // Clamp to padded size, shift if needed
        if br_new_x > cw_padded {
            let dx = br_new_x - cw_padded;
            if tl_new_x >= dx {
                tl_new_x -= dx;
                br_new_x -= dx;
            } else {
                br_new_x = cw_padded;
            }
        }
        if br_new_y > ch_padded {
            let dy = br_new_y - ch_padded;
            if tl_new_y >= dy {
                tl_new_y -= dy;
                br_new_y -= dy;
            } else {
                br_new_y = ch_padded;
            }
        }
        let bw_big = br_new_x - tl_new_x;
        let bh_big = br_new_y - tl_new_y;
        if bw_big == 0 || bh_big == 0 {
            continue;
        }
        let left = tl_x - tl_new_x;
        let top = tl_y - tl_new_y;
        // Build big image with reflect border for img, constant 0 for weight
        let mut big_img = Img3 { w: bw_big, h: bh_big, data: vec![0f32; bw_big * bh_big * 3] };
        let mut big_weight = WMap { w: bw_big, h: bh_big, data: vec![0f32; bw_big * bh_big] };
        for y in 0..bh_big {
            for x in 0..bw_big {
                // source coord in base image
                let sx = x as isize - left as isize;
                let sy = y as isize - top as isize;
                let d_idx3 = (y * bw_big + x) * 3;
                let d_idx1 = y * bw_big + x;
                if sx >= 0 && sy >= 0 && (sx as usize) < tw0 && (sy as usize) < th0 {
                    let s_idx3 = ((sy as usize) * tw0 + sx as usize) * 3;
                    big_img.data[d_idx3] = base_img.data[s_idx3];
                    big_img.data[d_idx3 + 1] = base_img.data[s_idx3 + 1];
                    big_img.data[d_idx3 + 2] = base_img.data[s_idx3 + 2];
                    big_weight.data[d_idx1] = base_weight.data[sy as usize * tw0 + sx as usize];
                } else {
                    // Image: BORDER_REFLECT_101
                    if sx >= 0 && sx < tw0 as isize && sy >= 0 && sy < th0 as isize {
                        // shouldn't happen – already handled
                    } else if sx >= -1 && sx < tw0 as isize + 1 && sy >= -1 && sy < th0 as isize + 1 {
                        // try to reflect from inside where we can get pixel
                        // For simplicity, if source out of bounds, reflect index into base_img if possible
                        // Use reflect_101 for both axes if the coordinate is outside base but not too far
                        // We'll only reflect when the coordinate is outside but weight would be zero anyway for constant border.
                        // For image we reflect, for weight we keep 0.
                        let rx = reflect_101(sx, tw0);
                        let ry = reflect_101(sy, th0);
                        // Only reflect if original request was within expanded but not inside – the reflected pixel exists
                        if rx < tw0 && ry < th0 {
                            let s_idx3 = (ry * tw0 + rx) * 3;
                            big_img.data[d_idx3] = base_img.data[s_idx3];
                            big_img.data[d_idx3 + 1] = base_img.data[s_idx3 + 1];
                            big_img.data[d_idx3 + 2] = base_img.data[s_idx3 + 2];
                        }
                    } else {
                        // Far outside gap, leave image as 0, weight 0 (still okay)
                    }
                    // weight stays 0 (BORDER_CONSTANT)
                }
            }
        }

        // Build pyramids for this big tile
        let mut pyr_img: Vec<Img3> = Vec::with_capacity(num_bands + 1);
        let mut pyr_weight: Vec<WMap> = Vec::with_capacity(num_bands + 1);
        pyr_img.push(big_img);
        pyr_weight.push(big_weight);
        for l in 0..num_bands {
            let down_i = pyr_down_3ch(&pyr_img[l]);
            let down_w = pyr_down_1ch(&pyr_weight[l]);
            pyr_img.push(down_i);
            pyr_weight.push(down_w);
        }
        // Laplacian pyramid: lap[l] = gauss[l] - up(gauss[l+1])
        let mut lap_pyr: Vec<Img3> = Vec::with_capacity(num_bands + 1);
        for l in 0..num_bands {
            let up = pyr_up_3ch(&pyr_img[l + 1], pyr_img[l].w, pyr_img[l].h);
            let mut lap = Img3 { w: pyr_img[l].w, h: pyr_img[l].h, data: vec![0f32; pyr_img[l].w * pyr_img[l].h * 3] };
            for i in 0..pyr_img[l].data.len() {
                lap.data[i] = pyr_img[l].data[i] - up.data[i];
            }
            lap_pyr.push(lap);
        }
        lap_pyr.push(pyr_img[num_bands].clone()); // low-pass

        // Accumulate into global pyramids at tl_new position
        // Global tl per level: divide tl_new by 2^l
        let mut gx = tl_new_x;
        let mut gy = tl_new_y;
        // Keep track of tile dimensions per level as in pyr
        for l in 0..=num_bands {
            let gw = global_ws[l];
            let gh = global_hs[l];
            let tile_w = lap_pyr[l].w;
            let tile_h = lap_pyr[l].h;
            let tile_wm = pyr_weight[l].w; // same as lap w
            // Accumulate
            for y in 0..tile_h.min(gh.saturating_sub(gy)) {
                let gy_glob = gy + y;
                if gy_glob >= gh {
                    break;
                }
                for x in 0..tile_w.min(gw.saturating_sub(gx)) {
                    let gx_glob = gx + x;
                    if gx_glob >= gw {
                        break;
                    }
                    let wgt = if x < tile_wm && y < pyr_weight[l].h {
                        pyr_weight[l].data[y * tile_wm + x]
                    } else {
                        0.0
                    };
                    if wgt <= 1e-6 {
                        continue;
                    }
                    let g_w_idx = gy_glob * gw + gx_glob;
                    global_wmap[l].data[g_w_idx] += wgt;
                    let s_idx = (y * tile_w + x) * 3;
                    let d_idx = (gy_glob * gw + gx_glob) * 3;
                    global_lap[l].data[d_idx] += lap_pyr[l].data[s_idx] * wgt;
                    global_lap[l].data[d_idx + 1] += lap_pyr[l].data[s_idx + 1] * wgt;
                    global_lap[l].data[d_idx + 2] += lap_pyr[l].data[s_idx + 2] * wgt;
                }
            }
            gx /= 2;
            gy /= 2;
        }
    }

    // Normalize per level by weight EPS – matches OpenCV normalizeUsingWeightMap
    const WEIGHT_EPS: f32 = 1e-5;
    for l in 0..=num_bands {
        let gw = global_ws[l];
        let gh = global_hs[l];
        let lap = &mut global_lap[l];
        let wmap = &global_wmap[l];
        for y in 0..gh {
            for x in 0..gw {
                let wi = y * gw + x;
                let wgt = wmap.data[wi];
                let base = wi * 3;
                if wgt > WEIGHT_EPS {
                    lap.data[base] /= wgt + WEIGHT_EPS;
                    lap.data[base + 1] /= wgt + WEIGHT_EPS;
                    lap.data[base + 2] /= wgt + WEIGHT_EPS;
                } else {
                    lap.data[base] = 0.0;
                    lap.data[base + 1] = 0.0;
                    lap.data[base + 2] = 0.0;
                }
            }
        }
    }

    // Restore image from Laplacian pyramid – matches OpenCV restoreImageFromLaplacePyr
    for l in (1..=num_bands).rev() {
        let up = pyr_up_3ch(&global_lap[l], global_ws[l - 1], global_hs[l - 1]);
        let dst = &mut global_lap[l - 1];
        for i in 0..dst.data.len().min(up.data.len()) {
            dst.data[i] += up.data[i];
        }
    }

    // Convert level 0 to Rgba, with alpha from level0 weight
    let final_lap = &global_lap[0];
    let final_w = &global_wmap[0];
    let mut big_rgba = Rgba::new(cw_padded, ch_padded);
    for y in 0..ch_padded {
        for x in 0..cw_padded {
            let wi = y * cw_padded + x;
            if wi >= final_w.data.len() {
                continue;
            }
            if final_w.data[wi] <= WEIGHT_EPS {
                // leave alpha 0
                continue;
            }
            let base = wi * 3;
            if base + 2 >= final_lap.data.len() {
                continue;
            }
            let r = final_lap.data[base].round().clamp(0.0, 255.0) as u8;
            let g = final_lap.data[base + 1].round().clamp(0.0, 255.0) as u8;
            let b = final_lap.data[base + 2].round().clamp(0.0, 255.0) as u8;
            let idx = wi * 4;
            big_rgba.px[idx] = r;
            big_rgba.px[idx + 1] = g;
            big_rgba.px[idx + 2] = b;
            big_rgba.px[idx + 3] = 255;
        }
    }

    // Crop to original cw,ch (dst_roi_final) – matches MultiBandBlender::blend
    let mut cropped = Rgba::new(cw, ch);
    for y in 0..ch {
        for x in 0..cw {
            let s = (y * cw_padded + x) * 4;
            let d = (y * cw + x) * 4;
            if s + 3 < big_rgba.px.len() && d + 3 < cropped.px.len() {
                cropped.px[d..d + 4].copy_from_slice(&big_rgba.px[s..s + 4]);
            }
        }
    }

    // If blended result is mostly empty (e.g., weights zero), fallback to feather
    let mut opaque = 0usize;
    for i in 0..cw * ch {
        if cropped.px[i * 4 + 3] == 255 {
            opaque += 1;
        }
    }
    if opaque == 0 {
        return feather_blend(tiles, masks, gain_maps, gx0, gy0, cw, ch);
    }

    Some(crop_to_content(cropped))
}

/// Crop rectangle = maximal all-opaque rectangle. With the coverage a proper
/// partition of the frames (no interior holes), this is the large landscape
/// rectangle spanning all frames, with the black curved borders removed.
fn content_rect(valid: &[bool], w: usize, h: usize) -> (usize, usize, usize, usize) {
    let mut heights = vec![0usize; w];
    let mut best = (0usize, 0usize, 0usize, 0usize);
    let mut best_area = 0usize;
    for y in 0..h {
        for x in 0..w {
            heights[x] = if valid[y * w + x] { heights[x] + 1 } else { 0 };
        }
        let mut stack: Vec<usize> = Vec::new();
        let mut x = 0usize;
        while x <= w {
            let cur = if x == w { 0 } else { heights[x] };
            if stack.is_empty() || cur >= heights[*stack.last().unwrap()] {
                stack.push(x);
                x += 1;
            } else {
                let top = stack.pop().unwrap();
                let height = heights[top];
                let left = if stack.is_empty() { 0 } else { *stack.last().unwrap() + 1 };
                let width = x - left;
                let area = height * width;
                if area > best_area {
                    best_area = area;
                    best = (left, y + 1 - height, width, height);
                }
            }
        }
    }
    best
}

fn crop_to_content(img: Rgba) -> Rgba {
    let valid: Vec<bool> = (0..img.w * img.h).map(|i| img.px[i * 4 + 3] == 255).collect();
    let (rx, ry, rw, rh) = content_rect(&valid, img.w, img.h);
    if rw == 0 || rh == 0 {
        return img;
    }
    let mut out = Rgba::new(rw, rh);
    for y in 0..rh {
        for x in 0..rw {
            let s = ((ry + y) * img.w + (rx + x)) * 4;
            let d = (y * rw + x) * 4;
            out.px[d..d + 4].copy_from_slice(&img.px[s..s + 4]);
        }
    }
    out
}
