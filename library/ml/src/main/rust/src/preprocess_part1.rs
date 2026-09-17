/// 1 when the bits being discarded round the kept value up, under round-to-nearest-even.
fn round_bit(bits: u32, shift: u32) -> u32 {
    if shift >= 32 {
        return 0;
    }
    let dropped = bits & ((1u32 << shift) - 1);
    let halfway = 1u32 << (shift - 1);
    if dropped > halfway || (dropped == halfway && (bits >> shift) & 1 == 1) {
        1
    } else {
        0
    }
}

/// fp16 to fp32, for reading a mask back off the device.
pub fn f16_to_f32(half: u16) -> f32 {
    let sign = ((half as u32) & 0x8000) << 16;
    let exponent = ((half >> 10) & 0x1f) as i32;
    let mantissa = ((half as u32) & 0x03ff) << 13;

    if exponent == 0 {
        if mantissa == 0 {
            return f32::from_bits(sign);
        }
        // Subnormal: renormalise by shifting until the leading bit clears the mantissa.
        let mut shifted = mantissa;
        let mut unbiased = -14;
        while shifted & 0x0080_0000 == 0 {
            shifted <<= 1;
            unbiased -= 1;
        }
        let biased = ((unbiased + 127) as u32) << 23;
        return f32::from_bits(sign | biased | (shifted & 0x007f_ffff));
    }
    if exponent == 0x1f {
        return f32::from_bits(sign | 0x7f80_0000 | mantissa);
    }
    let biased = ((exponent - 15 + 127) as u32) << 23;
    f32::from_bits(sign | biased | mantissa)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn argb(r: u8, g: u8, b: u8) -> i32 {
        (0xffu32 << 24 | (r as u32) << 16 | (g as u32) << 8 | b as u32) as i32
    }

    #[test]
    fn a_solid_colour_survives_the_rescale_unchanged() {
        let pixels = vec![argb(255, 128, 0); 4];
        let shape = Shape::new(3, 2, 2);
        let mut out = vec![0u16; shape.len() as usize];
        to_planar_f16(&pixels, 2, 2, shape, &RESCALE_ONLY, &mut out).expect("preprocesses");
        // Planar, so the first four entries are all red, the next four all green.
        for i in 0..4 {
            assert!((f16_to_f32(out[i]) - 1.0).abs() < 1e-3, "red {i}");
            assert!((f16_to_f32(out[4 + i]) - 128.0 / 255.0).abs() < 1e-3, "green {i}");
            assert_eq!(f16_to_f32(out[8 + i]), 0.0, "blue {i}");
        }
    }

    #[test]
    fn imagenet_normalisation_matches_a_hand_computation() {
        let pixels = vec![argb(0, 255, 128)];
        let shape = Shape::new(3, 1, 1);
        let mut out = vec![0u16; 3];
        to_planar_f16(&pixels, 1, 1, shape, &IMAGENET, &mut out).expect("preprocesses");
        let expected = [
            (0.0 - 0.485) / 0.229,
            (1.0 - 0.456) / 0.224,
            (128.0 / 255.0 - 0.406) / 0.225,
        ];
        for (i, want) in expected.iter().enumerate() {
            let got = f16_to_f32(out[i]);
            assert!((got - want).abs() < 2e-3, "channel {i}: {got} vs {want}");
        }
    }

    #[test]
    fn channels_are_planar_and_in_rgb_order() {
        // A transposed or BGR input is the single most likely preprocessing bug and
        // produces a plausible-looking mask, so it is pinned with distinct values.
        let pixels = vec![argb(10, 20, 30)];
        let shape = Shape::new(3, 1, 1);
        let mut out = vec![0u16; 3];
        to_planar_f16(&pixels, 1, 1, shape, &RESCALE_ONLY, &mut out).expect("preprocesses");
        let got: Vec<f32> = out.iter().map(|&h| f16_to_f32(h) * 255.0).collect();
        assert!((got[0] - 10.0).abs() < 0.5, "{got:?}");
        assert!((got[1] - 20.0).abs() < 0.5, "{got:?}");
        assert!((got[2] - 30.0).abs() < 0.5, "{got:?}");
    }

    #[test]
    fn a_bgr_normalisation_writes_blue_into_channel_zero() {
        // Both PP-OCRv5 exports decode BGR. Feeding a BGR net RGB is a shift no output
        // range reveals — the mask or the probability map still looks like one — so the
        // order is pinned with three distinct values.
        let pixels = vec![argb(10, 20, 30)];
        let shape = Shape::new(3, 1, 1);
        let mut out = vec![0u16; 3];
        let bgr = Normalise { mean: [0.0; 3], std: [1.0; 3], bgr: true };
        to_planar_f16(&pixels, 1, 1, shape, &bgr, &mut out).expect("preprocesses");
        let got: Vec<f32> = out.iter().map(|&h| f16_to_f32(h) * 255.0).collect();
        assert!((got[0] - 30.0).abs() < 0.5, "blue first: {got:?}");
        assert!((got[1] - 20.0).abs() < 0.5, "{got:?}");
        assert!((got[2] - 10.0).abs() < 0.5, "{got:?}");
    }

    #[test]
    fn the_ppocr_normalisations_reuse_the_existing_affines() {
        // Detection reuses ImageNet's constants verbatim — only the order differs.
        assert_eq!(PPOCR_DET.mean, IMAGENET.mean);
        assert_eq!(PPOCR_DET.std, IMAGENET.std);
        // Recognition's affine is FACE_EMBED's, and deliberately a separate constant so
        // that the two cannot drift into one and lose the channel order with it.
        assert_eq!(PPOCR_REC.mean, FACE_EMBED.mean);
        assert_eq!(PPOCR_REC.std, FACE_EMBED.std);
    }

    #[test]
    fn a_two_pixel_upscale_interpolates_at_half_pixel_centres() {
        // Source 0 and 255 across two columns, upscaled to four. With half-pixel
        // centres the samples land at source x = -0.25, 0.25, 0.75, 1.25, which clamp
        // and interpolate to 0, 63.75, 191.25, 255.
        let pixels = vec![argb(0, 0, 0), argb(255, 255, 255)];
        let shape = Shape::new(3, 1, 4);
        let mut out = vec![0u16; shape.len() as usize];
        to_planar_f16(&pixels, 2, 1, shape, &RESCALE_ONLY, &mut out).expect("preprocesses");
        let got: Vec<f32> = (0..4).map(|i| f16_to_f32(out[i]) * 255.0).collect();
        let want = [0.0, 63.75, 191.25, 255.0];
        for (i, w) in want.iter().enumerate() {
            assert!((got[i] - w).abs() < 0.6, "column {i}: {got:?} vs {want:?}");
        }
    }

    #[test]
    fn a_downscale_averages_rather_than_picking_a_corner() {
        // 2x2 of 0, 255, 255, 0 down to 1x1. Half-pixel puts the sample at the centre,
        // so all four taps weigh equally and the answer is the mean.
        let pixels = vec![argb(0, 0, 0), argb(255, 255, 255), argb(255, 255, 255), argb(0, 0, 0)];
        let shape = Shape::new(3, 1, 1);
        let mut out = vec![0u16; 3];
        to_planar_f16(&pixels, 2, 2, shape, &RESCALE_ONLY, &mut out).expect("preprocesses");
        let got = f16_to_f32(out[0]) * 255.0;
        assert!((got - 127.5).abs() < 0.6, "{got}");
    }

    #[test]
    fn half_conversion_round_trips_the_values_that_matter() {
        for value in [0.0f32, 1.0, -1.0, 0.5, -0.5, 2.2, -2.2, 1.0 / 255.0, 65504.0] {
            let back = f16_to_f32(f32_to_f16(value));
            let tolerance = value.abs() * 1e-3 + 1e-7;
            assert!((back - value).abs() <= tolerance, "{value} became {back}");
        }
    }

    #[test]
    fn half_conversion_rounds_to_nearest_even() {
        // 1 + 2^-11 sits exactly halfway between 1.0 and the next fp16, so it must go
        // to the even one, which is 1.0. Truncation would also give 1.0, so the
        // companion case below is what distinguishes them.
        assert_eq!(f32_to_f16(1.0 + 2f32.powi(-11)), f32_to_f16(1.0));
        // 1 + 3 * 2^-11 is halfway between the first and second fp16 above 1.0, and the
        // even neighbour is the second. Truncation would give the first.
        assert_eq!(f16_to_f32(f32_to_f16(1.0 + 3.0 * 2f32.powi(-11))), 1.0 + 2f32.powi(-9));
    }

    #[test]
    fn half_conversion_saturates_and_keeps_nan() {
        assert_eq!(f16_to_f32(f32_to_f16(1e30)), f32::INFINITY);
        assert_eq!(f16_to_f32(f32_to_f16(-1e30)), f32::NEG_INFINITY);
        assert!(f16_to_f32(f32_to_f16(f32::NAN)).is_nan());
        // Underflow keeps its sign, so a mask never gains a positive value from one.
        assert!(f16_to_f32(f32_to_f16(-1e-30)).is_sign_negative());
    }

    #[test]
    fn the_scrfd_and_embedder_normalisations_share_a_mean_but_not_a_divisor() {
        // Both are `(v - 127.5) / d`, with d = 128 and d = 127.5. The 0.4% difference is
        // invisible in any output, so the two constants are pinned against a direct
        // computation on the 0..255 scale rather than against each other.
        let through = |norm: &Normalise, raw: f32| (raw / 255.0 - norm.mean[0]) / norm.std[0];
        for raw in [0.0f32, 127.5, 255.0] {
            assert!((through(&SCRFD, raw) - (raw - 127.5) / 128.0).abs() < 1e-6, "{raw}");
            assert!(
                (through(&FACE_EMBED, raw) - (raw - 127.5) / 127.5).abs() < 1e-6,
                "{raw}"
            );
        }
        // And they really are different, so a swap is a change.
        assert_ne!(SCRFD.std[0], FACE_EMBED.std[0]);
    }

    #[test]
    fn letterbox_padding_is_normalised_zero_and_not_zero() {
        // `scrfd.cpp` pads with raw 0 *before* it normalises, so the border the net sees
        // is (0 - 127.5) / 128. Filling with 0.0 instead — the obvious reading of "pad
        // with zero" — puts a mid-grey frame around every non-square photo.
        //
        // 64x20 at a long side of 64: no scaling, and the height pads 20 up to 32, so
        // 6 rows above and 6 below.
        let pixels = vec![argb(255, 255, 255); 64 * 20];
        let fit = Letterbox::new(64, 20, 64, 32).expect("fits");
        assert_eq!(fit.resized, (64, 20));
        assert_eq!(fit.padded, (64, 32));
        assert_eq!(fit.offset, (0, 6));

        let shape = fit.shape();
        let mut out = vec![0u16; shape.len() as usize];
        to_letterboxed_f16(&pixels, 64, 20, &fit, &SCRFD, &mut out).expect("letterboxes");

        let want_border = (0.0 - 127.5) / 128.0;
        let want_image = (255.0 - 127.5) / 128.0;
        let row = |y: u32| f16_to_f32(out[(y * shape.w) as usize]);
        // Row 0 and row 5 are padding, row 6 is the first image row.
        assert!((row(0) - want_border).abs() < 2e-3, "row 0 is {}", row(0));
        assert!((row(5) - want_border).abs() < 2e-3, "row 5 is {}", row(5));
        assert!((row(6) - want_image).abs() < 2e-3, "row 6 is {}", row(6));
        // Row 25 is the last image row, 26 and 31 are the bottom padding.
        assert!((row(25) - want_image).abs() < 2e-3, "row 25 is {}", row(25));
        assert!((row(26) - want_border).abs() < 2e-3, "row 26 is {}", row(26));
        assert!((row(31) - want_border).abs() < 2e-3, "row 31 is {}", row(31));
    }

    #[test]
    fn a_letterboxed_image_lands_centred_at_the_offset() {
        // A single white column in an otherwise black source, so its position after
        // padding is unambiguous. 20x64 portrait at a long side of 64: no scaling, and
        // the width pads 20 up to 32, so 6 columns on the left.
        let mut pixels = vec![argb(0, 0, 0); 20 * 64];
        for row in 0..64 {
            pixels[row * 20 + 10] = argb(255, 255, 255);
        }
        let fit = Letterbox::new(20, 64, 64, 32).expect("fits");
        assert_eq!(fit.resized, (20, 64));
        assert_eq!(fit.padded, (32, 64));
        assert_eq!(fit.offset, (6, 0));

        let shape = fit.shape();
        let mut out = vec![0u16; shape.len() as usize];
        to_letterboxed_f16(&pixels, 20, 64, &fit, &RESCALE_ONLY, &mut out)
            .expect("letterboxes");

        // The bright column was at source x = 10, so it is at output x = 10 + 6 = 16.
        let row = (shape.h / 2 * shape.w) as usize;
        let brightest = (0..shape.w)
            .max_by(|&a, &b| {
                f16_to_f32(out[row + a as usize]).total_cmp(&f16_to_f32(out[row + b as usize]))
            })
            .expect("a row");
        assert_eq!(brightest, 16);
        // And the padding either side really is the border, not a smeared edge pixel.
        assert_eq!(f16_to_f32(out[row]), 0.0);
        assert_eq!(f16_to_f32(out[row + 31]), 0.0);
    }

    #[test]
    fn a_square_letterbox_pads_both_axes_and_keeps_the_scale() {
        // The same 1280x720 photo as the tight-fit test: scale 0.5 and a 640x360 image
        // either way, but padded to 640x640 with 140 rows above instead of 12.
        let tight = Letterbox::new(1280, 720, 640, 32).expect("fits");
        let square = Letterbox::square(1280, 720, 640).expect("fits");
        assert_eq!(square.scale, tight.scale);
        assert_eq!(square.resized, tight.resized);
        assert_eq!(square.padded, (640, 640));
        assert_eq!(square.offset, (0, 140));
    }

    #[test]
    fn a_square_letterbox_recovers_the_original_fraction() {
        // The undo has to work against the larger offset too, which is the whole reason
        // the offset is stored rather than recomputed.
        let fit = Letterbox::square(1280, 720, 640).expect("fits");
        let mut faces = vec![super::super::post::nms::Face {
            score: 0.9,
            bounds: [0.0, 140.0, 640.0, 500.0],
            keypoints: [(320.0, 320.0); 5],
        }];
        super::super::post::nms::to_source(&mut faces, &fit, 1280, 720);
        let got = faces.first().copied().expect("one face");
        assert!((got.bounds[0] - 0.0).abs() < 1e-6, "{:?}", got.bounds);
        assert!((got.bounds[1] - 0.0).abs() < 1e-6, "{:?}", got.bounds);
        assert!((got.bounds[2] - 1279.0 / 1280.0).abs() < 1e-6, "{:?}", got.bounds);
        assert!((got.bounds[3] - 719.0 / 720.0).abs() < 1e-6, "{:?}", got.bounds);
        // The centre of the padded square is the centre of the photo.
        let (x, y) = got.keypoints[0];
        assert!((x - 0.5).abs() < 2e-3, "{x}");
        assert!((y - 0.5).abs() < 2e-3, "{y}");
    }

    #[test]
    fn a_square_letterbox_is_always_exactly_the_side_it_was_asked_for() {
        for (width, height) in [
            (4032, 3024),
            (3024, 4032),
            (1920, 1080),
            (640, 480),
            (1, 4000),
            (4000, 1),
            (1, 1),
            (639, 641),
        ] {
            let fit = Letterbox::square(width, height, 640).expect("fits");
            assert_eq!(fit.padded, (640, 640), "{width}x{height}");
            // The image must fit inside the square with the padding it claims.
            assert!(
                fit.offset.0 + fit.resized.0 <= 640 && fit.offset.1 + fit.resized.1 <= 640,
                "{width}x{height}: {fit:?}"
            );
        }
    }

    #[test]
    fn a_mismatched_letterbox_output_length_is_refused() {
        let pixels = vec![argb(0, 0, 0); 4];
        let fit = Letterbox::new(2, 2, 64, 32).expect("fits");
        let mut out = vec![0u16; 8];
        let error = to_letterboxed_f16(&pixels, 2, 2, &fit, &SCRFD, &mut out)
            .expect_err("wrong length");
        assert!(error.contains("output elements"), "{error}");
    }

    #[test]
    fn a_mismatched_output_length_is_refused() {
        let pixels = vec![argb(0, 0, 0)];
        let mut out = vec![0u16; 2];
        let error = to_planar_f16(&pixels, 1, 1, Shape::new(3, 1, 1), &RESCALE_ONLY, &mut out)
            .expect_err("wrong length");
        assert!(error.contains("2 output elements"), "{error}");
    }
}
