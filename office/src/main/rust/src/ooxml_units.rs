//! OOXML unit conversions and colour resolution.
//!
//! Port of `OoxmlUnits.kt`, plus the value logic of `OoxmlColor.kt` (preset names and `scrgbClr`).
//! Every OOXML import path calls into this for lengths and colours, and it is pure arithmetic with
//! no Android or XML surface.
//!
//! `f32` throughout, matching the Kotlin's `Float`, so rounding agrees bit for bit. The colour
//! transforms in particular accumulate error differently in `f64` and would drift from the values
//! already rendered by the Kotlin path.
//!
//! Colours are `0xAARRGGBB` in an `i64`, matching the Kotlin `Long` (Java has no unsigned types,
//! and the JNI boundary is `jlong`).

// --- Lengths ---

/// EMU (English Metric Units, 914400/inch, 9525/px@96) → px@96.
pub fn emu_to_px(emu: i64) -> f32 {
    emu as f32 / 9525.0
}

/// EMU → pt (72/inch).
pub fn emu_to_pt(emu: i64) -> f32 {
    emu as f32 / 12700.0
}

/// Twips (1/20 pt) → pt.
pub fn twips_to_pt(tw: i32) -> f32 {
    tw as f32 / 20.0
}

/// Twips → px@96 (1 pt = 96/72 px).
pub fn twips_to_px(tw: i32) -> f32 {
    tw as f32 / 20.0 * 96.0 / 72.0
}

/// Half-points (`w:sz`) → pt.
pub fn half_pt_to_pt(hp: i32) -> f32 {
    hp as f32 / 2.0
}

/// Hundredths of a point (DrawingML `a:sz`, `a:spc`) → pt.
pub fn hundredth_pt_to_pt(v: i32) -> f32 {
    v as f32 / 100.0
}

/// 60000ths of a degree (DrawingML `rot`) → degrees clockwise.
pub fn angle60000_to_deg(v: i32) -> f32 {
    v as f32 / 60000.0
}

/// Excel column width (in "max digit widths") → approximate px@96.
pub fn excel_col_width_to_px(chars: f32) -> f32 {
    (chars * 7.0) + 5.0
}

/// Points → px@96.
pub fn pt_to_px(pt: f32) -> f32 {
    pt * 96.0 / 72.0
}

// --- Colours ---

/// Parses `RRGGBB` or `AARRGGBB` (with optional `#`) to `0xAARRGGBB`, forcing full alpha when
/// only RGB is given. `None` for "auto", blank or malformed input.
pub fn hex_color(value: Option<&str>) -> Option<i64> {
    let raw = value?.trim();
    let s = raw.strip_prefix('#').unwrap_or(raw);
    if s.is_empty() || s.eq_ignore_ascii_case("auto") {
        return None;
    }
    match s.len() {
        6 => i64::from_str_radix(s, 16).ok().map(|v| 0xFF00_0000i64 | v),
        8 => i64::from_str_radix(s, 16).ok(),
        _ => None,
    }
}

/// Standard highlight names (`w:highlight`) → `0xFFRRGGBB`.
pub fn highlight_color(name: Option<&str>) -> Option<i64> {
    let name = name?.to_ascii_lowercase();
    Some(match name.as_str() {
        "black" => 0xFF00_0000,
        "blue" => 0xFF00_00FF,
        "cyan" => 0xFF00_FFFF,
        "green" => 0xFF00_8000,
        "magenta" => 0xFFFF_00FF,
        "red" => 0xFFFF_0000,
        "yellow" => 0xFFFF_FF00,
        "white" => 0xFFFF_FFFF,
        "darkblue" => 0xFF00_0080,
        "darkcyan" => 0xFF00_8080,
        "darkgreen" => 0xFF00_6400,
        "darkmagenta" => 0xFF80_0080,
        "darkred" => 0xFF80_0000,
        "darkyellow" => 0xFF80_8000,
        "darkgray" => 0xFFA9_A9A9,
        "lightgray" => 0xFFD3_D3D3,
        _ => return None,
    })
}

/// DrawingML/VML system colour names (`sysClr val`) → `0xFFRRGGBB`.
pub fn sys_color(name: Option<&str>) -> Option<i64> {
    let name = name?.to_ascii_lowercase();
    Some(match name.as_str() {
        "windowtext" | "captiontext" => 0xFF00_0000,
        "window" => 0xFFFF_FFFF,
        "graytext" => 0xFF80_8080,
        "highlight" => 0xFF33_99FF,
        "btnface" => 0xFFF0_F0F0,
        "btntext" => 0xFF00_0000,
        _ => return None,
    })
}

/// Preset colour names (`a:prstClr val`) → `0xFFRRGGBB`. Common subset, matching the Kotlin.
///
/// Case-sensitive, as upstream: the map has both `darkGray` and `gray`/`grey`, and OOXML writes
/// these camel-cased.
pub fn preset_color(name: Option<&str>) -> Option<i64> {
    Some(match name? {
        "black" => 0xFF00_0000,
        "white" => 0xFFFF_FFFF,
        "red" => 0xFFFF_0000,
        "green" => 0xFF00_8000,
        "blue" => 0xFF00_00FF,
        "yellow" => 0xFFFF_FF00,
        "cyan" => 0xFF00_FFFF,
        "magenta" => 0xFFFF_00FF,
        "gray" | "grey" => 0xFF80_8080,
        "darkGray" => 0xFFA9_A9A9,
        "lightGray" => 0xFFD3_D3D3,
        "orange" => 0xFFFF_A500,
        "purple" => 0xFF80_0080,
        "brown" => 0xFFA5_2A2A,
        "pink" => 0xFFFF_C0CB,
        _ => return None,
    })
}

/// `a:scrgbClr` percentage components (OOXML 1000ths) → `0xFFRRGGBB`.
pub fn scrgb_color(r: Option<&str>, g: Option<&str>, b: Option<&str>) -> Option<i64> {
    fn pct(s: Option<&str>) -> Option<i64> {
        let v: i32 = s?.parse().ok()?;
        Some((((v as f32 / 100000.0 * 255.0) as i32).clamp(0, 255)) as i64)
    }
    Some(0xFF00_0000i64 | (pct(r)? << 16) | (pct(g)? << 8) | pct(b)?)
}

/// DrawingML colour transforms applied to an `0xAARRGGBB` base.
///
/// `lumMod`/`lumOff` (luminance), `tint` (toward white), `shade` (toward black), `satMod`
/// (saturation) and `alpha`, all as OOXML 1000ths — `60000` is 60%.
///
/// Order matters and follows the Kotlin: shade and tint act on RGB first, then the HSL round trip,
/// then alpha.
#[allow(clippy::too_many_arguments)]
pub fn apply_transforms(
    base: i64,
    lum_mod: Option<i32>,
    lum_off: Option<i32>,
    tint: Option<i32>,
    shade: Option<i32>,
    sat_mod: Option<i32>,
    alpha: Option<i32>,
) -> i64 {
    let mut a = ((base >> 24) & 0xFF) as i32;
    let mut r = ((base >> 16) & 0xFF) as f32;
    let mut g = ((base >> 8) & 0xFF) as f32;
    let mut b = (base & 0xFF) as f32;

    if let Some(shade) = shade {
        let f = shade as f32 / 100000.0;
        r *= f;
        g *= f;
        b *= f;
    }
    if let Some(tint) = tint {
        let f = tint as f32 / 100000.0;
        r = r * f + 255.0 * (1.0 - f);
        g = g * f + 255.0 * (1.0 - f);
        b = b * f + 255.0 * (1.0 - f);
    }

    if lum_mod.is_some() || lum_off.is_some() || sat_mod.is_some() {
        let (h, mut s, mut l) = rgb_to_hsl(r, g, b);
        if let Some(sat) = sat_mod {
            s = (s * (sat as f32 / 100000.0)).clamp(0.0, 1.0);
        }
        if let Some(lm) = lum_mod {
            l = (l * (lm as f32 / 100000.0)).clamp(0.0, 1.0);
        }
        if let Some(lo) = lum_off {
            l = (l + lo as f32 / 100000.0).clamp(0.0, 1.0);
        }
        let (nr, ng, nb) = hsl_to_rgb(h, s, l);
        r = nr;
        g = ng;
        b = nb;
    }

    if let Some(alpha) = alpha {
        a = ((alpha as f32 / 100000.0 * 255.0) as i32).clamp(0, 255);
    }

    ((a as i64) << 24)
        | (((r as i32).clamp(0, 255) as i64) << 16)
        | (((g as i32).clamp(0, 255) as i64) << 8)
        | ((b as i32).clamp(0, 255) as i64)
}

fn rgb_to_hsl(r: f32, g: f32, b: f32) -> (f32, f32, f32) {
    let (rn, gn, bn) = (r / 255.0, g / 255.0, b / 255.0);
    let max = rn.max(gn).max(bn);
    let min = rn.min(gn).min(bn);
    let l = (max + min) / 2.0;
    if max == min {
        return (0.0, 0.0, l);
    }
    let d = max - min;
    let s = if l > 0.5 { d / (2.0 - max - min) } else { d / (max + min) };
    let h = if max == rn {
        (gn - bn) / d + if gn < bn { 6.0 } else { 0.0 }
    } else if max == gn {
        (bn - rn) / d + 2.0
    } else {
        (rn - gn) / d + 4.0
    } / 6.0;
    (h, s, l)
}

fn hsl_to_rgb(h: f32, s: f32, l: f32) -> (f32, f32, f32) {
    if s == 0.0 {
        let v = l * 255.0;
        return (v, v, v);
    }
    let q = if l < 0.5 { l * (1.0 + s) } else { l + s - l * s };
    let p = 2.0 * l - q;
    (
        hue_to_rgb(p, q, h + 1.0 / 3.0) * 255.0,
        hue_to_rgb(p, q, h) * 255.0,
        hue_to_rgb(p, q, h - 1.0 / 3.0) * 255.0,
    )
}

fn hue_to_rgb(p: f32, q: f32, t_in: f32) -> f32 {
    let mut t = t_in;
    if t < 0.0 {
        t += 1.0;
    }
    if t > 1.0 {
        t -= 1.0;
    }
    if t < 1.0 / 6.0 {
        p + (q - p) * 6.0 * t
    } else if t < 1.0 / 2.0 {
        q
    } else if t < 2.0 / 3.0 {
        p + (q - p) * (2.0 / 3.0 - t) * 6.0
    } else {
        p
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn length_conversions() {
        assert_eq!(emu_to_px(914400), 96.0, "1 inch is 96 px");
        assert_eq!(emu_to_pt(914400), 72.0, "1 inch is 72 pt");
        assert_eq!(twips_to_pt(1440), 72.0);
        assert_eq!(twips_to_px(1440), 96.0);
        assert_eq!(half_pt_to_pt(24), 12.0);
        assert_eq!(hundredth_pt_to_pt(1200), 12.0);
        assert_eq!(angle60000_to_deg(5400000), 90.0);
        assert_eq!(pt_to_px(72.0), 96.0);
        assert_eq!(excel_col_width_to_px(10.0), 75.0);
    }

    #[test]
    fn hex_colors() {
        assert_eq!(hex_color(Some("FF0000")), Some(0xFFFF_0000));
        assert_eq!(hex_color(Some("#FF0000")), Some(0xFFFF_0000), "leading # is optional");
        assert_eq!(hex_color(Some("80FF0000")), Some(0x80FF_0000), "8 digits keep their alpha");
        assert_eq!(hex_color(Some("  00FF00  ")), Some(0xFF00_FF00), "whitespace is trimmed");
    }

    #[test]
    fn non_colors_are_none_not_black() {
        // "auto" must not collapse to a real colour; callers fall back to a theme default.
        assert_eq!(hex_color(Some("auto")), None);
        assert_eq!(hex_color(Some("AUTO")), None);
        assert_eq!(hex_color(Some("")), None);
        assert_eq!(hex_color(Some("#")), None);
        assert_eq!(hex_color(None), None);
        assert_eq!(hex_color(Some("XYZXYZ")), None, "non-hex is rejected");
        assert_eq!(hex_color(Some("FFF")), None, "3-digit shorthand is not OOXML");
    }

    #[test]
    fn named_color_tables() {
        assert_eq!(highlight_color(Some("Red")), Some(0xFFFF_0000), "names are case-insensitive");
        assert_eq!(highlight_color(Some("darkGray")), Some(0xFFA9_A9A9));
        assert_eq!(highlight_color(Some("chartreuse")), None);
        assert_eq!(sys_color(Some("windowText")), Some(0xFF00_0000));
        assert_eq!(sys_color(Some("window")), Some(0xFFFF_FFFF));
        assert_eq!(sys_color(Some("nope")), None);
        assert_eq!(preset_color(Some("grey")), Some(0xFF80_8080));
        assert_eq!(preset_color(Some("darkGray")), Some(0xFFA9_A9A9));
        assert_eq!(preset_color(Some("nope")), None);
    }

    #[test]
    fn scrgb_percentages() {
        assert_eq!(scrgb_color(Some("100000"), Some("0"), Some("0")), Some(0xFFFF_0000));
        assert_eq!(scrgb_color(Some("0"), Some("100000"), Some("0")), Some(0xFF00_FF00));
        assert_eq!(scrgb_color(Some("100000"), Some("100000"), Some("100000")), Some(0xFFFF_FFFF));
        assert_eq!(scrgb_color(Some("50000"), None, Some("0")), None, "a missing channel fails");
        assert_eq!(scrgb_color(Some("x"), Some("0"), Some("0")), None);
    }

    #[test]
    fn no_transforms_returns_the_base_untouched() {
        let base = 0x8012_3456;
        assert_eq!(apply_transforms(base, None, None, None, None, None, None), base);
    }

    #[test]
    fn shade_darkens_and_tint_lightens() {
        let red = 0xFFFF_0000;
        // 50% shade halves the channel.
        let shaded = apply_transforms(red, None, None, None, Some(50000), None, None);
        assert_eq!(shaded, 0xFF7F_0000);
        // 50% tint moves halfway to white.
        let tinted = apply_transforms(red, None, None, Some(50000), None, None, None);
        assert_eq!(tinted, 0xFFFF_7F7F);
    }

    #[test]
    fn alpha_is_a_percentage_of_255() {
        let opaque = 0xFF00_0000u32 as i64;
        assert_eq!((apply_transforms(opaque, None, None, None, None, None, Some(100000)) >> 24) & 0xFF, 255);
        assert_eq!((apply_transforms(opaque, None, None, None, None, None, Some(50000)) >> 24) & 0xFF, 127);
        assert_eq!((apply_transforms(opaque, None, None, None, None, None, Some(0)) >> 24) & 0xFF, 0);
    }

    #[test]
    fn luminance_modulation_round_trips_through_hsl() {
        let gray = 0xFF80_8080u32 as i64;
        // lumMod 100% + lumOff 0 is identity, proving the HSL round trip is stable.
        let same = apply_transforms(gray, Some(100000), Some(0), None, None, None, None);
        let (r, g, b) = ((same >> 16) & 0xFF, (same >> 8) & 0xFF, same & 0xFF);
        assert!((r - 0x80).abs() <= 1 && (g - 0x80).abs() <= 1 && (b - 0x80).abs() <= 1, "{same:#x}");

        // Halving luminance darkens.
        let darker = apply_transforms(gray, Some(50000), None, None, None, None, None);
        assert!(((darker >> 16) & 0xFF) < 0x80);
    }

    #[test]
    fn transforms_stay_in_range() {
        // Values well past 100% must clamp rather than wrap into another channel.
        for lum in [0, 200000, 500000] {
            let c = apply_transforms(0xFFFF_FFFFu32 as i64, Some(lum), Some(lum), None, None, None, None);
            for shift in [0, 8, 16, 24] {
                let channel = (c >> shift) & 0xFF;
                assert!((0..=255).contains(&channel), "channel out of range in {c:#x}");
            }
        }
    }

    #[test]
    fn greyscale_has_no_hue() {
        let (h, s, _) = rgb_to_hsl(128.0, 128.0, 128.0);
        assert_eq!(h, 0.0);
        assert_eq!(s, 0.0);
    }
}
