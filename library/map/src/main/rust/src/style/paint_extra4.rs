//! Flat-style tests, part 2: pixel widths, road splits, rail, fills and symbols.
//!
//! Split from [`paint`]'s test module so each file stays small.

use super::paint::{MAX_ZOOM, Ramp};
use super::paint_extra2::find;
use crate::style::{LayerKind, layers};

    /// What should actually appear
    /// This is the closest a host test gets to the device, so the numbers are spelled out.
    #[test]
    fn the_pixel_widths_at_the_measured_zooms_are_what_the_style_asks_for() {
        let of = |id: &str, zoom: f64| -> Option<f32> {
            let stroke = find(id).stroke(zoom);
            // `None` means the renderer skips the layer outright.
            stroke.visible().then(|| stroke.half_px(3.0).0 * 2.0)
        };

        // z3.87, where road colour covered 19.72% of the viewport. The casing is gated out by
        // its own ramp, and the fill is a hairline the shader now fades rather than rounding
        // up to a solid pixel.
        assert_eq!(of("roads-highway-casing", 3.87), None);
        let hairline = of("roads-highway", 3.87).expect("drawn");
        assert!(hairline < 1.0, "{hairline} px should be sub-pixel at z3.87");
        // Was a 6.0 Dp fill plus a 1.25 Dp casing 6.0 Dp apart: 18 px of fill on a density-3
        // screen, which is the 15-25 px ribbon that merged adjacent roads into sheets.

        // z7.87, where coverage was 6.76% and the network was already recognisable.
        let highway = of("roads-highway", 7.87).expect("drawn");
        assert!((3.0..4.0).contains(&highway), "{highway} px");

        // z14.87, which looked correct and must still look correct.
        let highway = of("roads-highway", 14.87).expect("drawn");
        assert!((13.0..15.0).contains(&highway), "{highway} px");
        let minor = of("roads-minor", 14.87).expect("drawn");
        assert!((5.0..6.0).contains(&minor), "{minor} px");
        assert!(minor < highway, "a minor road must be narrower than a highway");
        // A link is narrower than the class road it leaves: at z14.87 the link ramp reads
        // ~2.24 Dp (~6.7 px) against the highway's ~4.73 Dp. Before the link layers
        // existed the ramp drew at full highway width, which is the too-wide super-zoom
        // roads this file's new layers fix.
        let link = of("roads-link", 14.87).expect("drawn");
        assert!((6.0..7.5).contains(&link), "{link} px");
        assert!(link < highway, "a slip road must be narrower than the highway it joins");
    }

    // --- the link/service split (issue #3) -----------------------------------

    /// The link casing carries the authored link-casing ramp, and the fill the link
    /// ramp — both narrower than the class layers (z18: 11 Dp vs 15 Dp with no link
    /// casing at all on the class side, since a link is not a highway).
    #[test]
    fn a_link_is_narrower_than_the_class_road_it_joins() {
        let (link, highway) = (find("roads-link"), find("roads-highway"));
        for tenth in [160, 170, 180] {
            let zoom = tenth as f64 / 10.0;
            let (l, h) = (link.stroke(zoom).width_dp, highway.stroke(zoom).width_dp);
            assert!(l > 0.0 && h > 0.0, "both drawn at z{zoom}");
            assert!(l < h, "link {l} Dp is not narrower than highway {h} Dp at z{zoom}");
        }
        assert_eq!(link.stroke(18.0).width_dp, 11.0, "the authored z18 link width");
        assert_eq!(highway.stroke(18.0).width_dp, 15.0);
        assert_eq!(link.gap_width.peak(), 0.0, "a link fill is a single centred stroke");
        assert_eq!(find("roads-link-casing").stroke(18.0).gap_width_dp, 11.0);
        // The highway casing's gap ramp ends at z18 with 15 Dp: a link must never be
        // outlined by it, which the `link` forbid-flag on the class layers guarantees
        // (see `surface_road_layers_draw_only_plain_surface_roads`).
        assert!(highway.forbid_flags & 0b100 != 0, "highway layers forbid links");
    }

    /// Service streets draw at the service width, not at full minor width.
    #[test]
    fn a_service_street_is_narrower_than_a_minor_road() {
        let (service, minor) = (find("roads-minor-service"), find("roads-minor"));
        assert_eq!(service.stroke(18.0).width_dp, 8.0, "the authored z18 service width");
        assert_eq!(minor.stroke(18.0).width_dp, 11.0);
        assert!(service.stroke(18.0).width_dp < minor.stroke(18.0).width_dp);
    }

    // --- rail: a dashed translucent line, not a solid road (issue #6) --------

    /// Rail renders as the authored dashed grey line: `[0.3, 0.75]` in line widths at
    /// 0.5 opacity in `#a7b1b3` — not the solid road-coloured band the comparator
    /// showed down the Market St corridor.
    #[test]
    fn rail_is_a_dashed_translucent_grey_line() {
        let rail = find("roads-rail");
        assert_eq!(rail.dash, (0.3, 0.75), "the authored line-dasharray");
        assert_eq!(rail.opacity_at(16.0), 0.5, "the authored line-opacity");
        assert_eq!(rail.light, 0xFFA7B1B3, "the authored line-color");
        // The width ramp is unchanged: the dash and translucency are the fix, not the
        // width.
        assert_eq!(rail.stroke(18.0).width_dp, 9.0);
    }

    // --- fills -------------------------------------------------------------

    /// The opacity ramp is the *only* thing that gates a fill, and it is continuous. Pinned at
    /// the zooms that were visibly wrong on device.
    #[test]
    fn the_opacity_ramp_is_the_only_gate_and_it_is_continuous() {
        for layer in layers().iter().filter(|l| l.kind == LayerKind::Fill) {
            let mut previous = f32::NAN;
            for tenth in 0..=(MAX_ZOOM as u32 * 10) {
                let zoom = tenth as f64 / 10.0;
                let opacity = layer.opacity_at(zoom);
                assert!((0.0..=1.0).contains(&opacity), "{} at z{zoom} is {opacity}", layer.id);
                // No step larger than the ramp's own slope between adjacent tenths: a jump
                // means something quantised the zoom, which is how a layer pops instead of
                // fading.
                if previous.is_finite() {
                    assert!(
                        (opacity - previous).abs() < 0.06,
                        "{} jumped {previous} -> {opacity} at z{zoom}",
                        layer.id,
                    );
                }
                previous = opacity;
            }
        }
    }

    /// The values that were visibly wrong when opacity was a baked alpha plus a zoom gate.
    #[test]
    fn fill_opacity_is_a_ramp_evaluated_per_frame_not_a_baked_alpha() {
        // Alpha stays out of the colour column: the ramp owns it, in both variants.
        for id in ["buildings", "landuse_urban_green", "earth", "landcover:glacier"] {
            assert_eq!(find(id).light >> 24, 0xFF, "{id}'s alpha belongs to its ramp");
            assert_eq!(find(id).dark >> 24, 0xFF);
        }
        let at = |id: &str, zoom: f64| find(id).opacity_at(zoom);
        assert_eq!(at("buildings", 16.0), 0.5, "a literal opacity");
        assert_eq!(at("landuse_urban_green", 16.0), 0.7);
        assert_eq!(at("earth", 4.0), 1.0, "no opacity is fully opaque");
        // The two ramps, at the zooms that were visibly wrong on device.
        assert_eq!(at("landcover", 5.0), 1.0);
        assert_eq!(at("landcover", 6.0), 0.5, "half, not the full blanket");
        assert_eq!(at("landcover", 7.0), 0.0);
        assert_eq!(at("landcover:grassland", 6.0), 0.5, "an arm carries its family's ramp");
        assert_eq!(at("landuse_park:national_park", 6.0), 0.0);
        assert!((at("landuse_park:wood", 7.0) - 0.2).abs() < 1e-6, "a fifth, not full green");
        assert_eq!(at("landuse_park:military", 11.0), 1.0);
        // A line layer is opaque; only fills carry an opacity ramp.
        assert_eq!(at("roads-major", 10.0), 1.0);
    }

    #[test]
    fn a_line_layer_has_a_width_and_a_fill_does_not() {
        for layer in layers() {
            match layer.kind {
                LayerKind::Line => {
                    // A casing needs a width OR a gap: gap-only casings
                    // (bridges-other: authored gap, no width — the casing IS
                    // the gap band) tessellate off the gap peak.
                    assert!(
                        layer.width.peak() > 0.0 || layer.gap_width.peak() > 0.0,
                        "{} needs a width",
                        layer.id
                    );
                    // A line's opacity is 1 except where the authored style paints it
                    // translucent — today only rail's 0.5, which is what makes a railway a
                    // faint dashed line rather than a solid road.
                    let expected_opacity = if layer.id == "roads-rail" { 0.5 } else { 1.0 };
                    assert_eq!(
                        layer.opacity,
                        Ramp::constant(expected_opacity),
                        "{} carries an opacity the authored style did not give it",
                        layer.id
                    );
                }
                LayerKind::Fill => {
                    assert_eq!(layer.width.peak(), 0.0, "{} is a fill", layer.id);
                    assert_eq!(layer.gap_width.peak(), 0.0, "{} is a fill", layer.id);
                    assert_eq!(layer.dash, (0.0, 0.0), "{} is a fill", layer.id);
                }
                LayerKind::Symbol => {
                    // Symbols carry text paint, not stroke paint.
                    assert!(layer.text_size.peak() > 0.0, "{} needs a text size", layer.id);
                    assert_eq!(layer.width.peak(), 0.0, "{} is a symbol", layer.id);
                    assert_eq!(layer.gap_width.peak(), 0.0, "{} is a symbol", layer.id);
                    // Halo width stays authored (1px everywhere in basemap.json);
                    // halo COLOR is deliberately high-contrast (white/dark) where
                    // the authored #e0e0e0 washes out on our land — pinned here,
                    // Halo width is 1px almost everywhere; locality takes 1.5
                    // for legibility at city sizes (pinned below).
                    assert!(
                        layer.halo_width == 1.0 || layer.id == "places-locality",
                        "{} halo width",
                        layer.id
                    );
                    assert!(layer.halo_light >> 24 > 0, "{} halo is fully transparent", layer.id);
                }
            }
        }
    }

    /// Symbol halos are high-contrast by decision, not transcription: white in
    /// light mode, the dark backdrop in dark mode. The authored #e0e0e0 halo
    /// is indistinguishable from our #e2dfda land, so labels smear instead of
    /// reading — the M1 legibility verdict. Width stays authored (cross-check).
    #[test]
    fn symbol_halos_contrast_against_both_land_and_text() {
        for id in ["places-country", "places-region", "places-locality", "places-subplace"] {
            let layer = find(id);
            assert_eq!(layer.halo_light, 0xFFFFFFFF, "{id} light halo");
            assert_eq!(layer.halo_dark, 0xFF0D1B2A, "{id} dark halo");
        }
    }

    /// Place labels carry the authored two-arm size, keyed on population rank.
    ///
    /// The authored `text-size` for country and locality is data-driven (`case` over
    /// `population_rank`), so the cross-check against `basemap.json` skips it and this
    /// pins the transcription instead. It used to be one collapsed ramp with a fixed
    /// 1.25x/0.85x nudge in `tile::symbol`, which drew a hamlet at close to city size —
    /// and since a collision box follows the label's size, those hamlets then beat the
    /// cities they overlapped.
    #[test]
    fn city_labels_track_the_big_city_arm_at_compared_zooms() {
        let size = |id: &str, zoom: f64, pop: u16| find(id).text_size_for(zoom, pop);

        // z10: the authored threshold is rank 9 (50k), 12px below and 20px above.
        assert_eq!(size("places-locality", 10.0, 13), 20.0, "a million-plus city");
        assert_eq!(size("places-locality", 10.0, 9), 20.0, "50k is on the threshold");
        assert_eq!(size("places-locality", 10.0, 8), 12.0, "a 20k town");
        assert_eq!(size("places-locality", 10.0, 0), 12.0, "uncounted");

        // z6: the threshold rises to rank 12 (500k), so a 200k town drops to the small arm.
        assert_eq!(size("places-locality", 6.0, 12), 17.0);
        assert_eq!(size("places-locality", 6.0, 11), 11.0);

        // Countries switch at rank 8 by z6.
        assert_eq!(size("places-country", 6.0, 8), 18.0);
        assert_eq!(size("places-country", 6.0, 7), 10.0);

        // Region and subplace are plain ramps in the authored style too, so they answer
        // the same size at every rank and the cross-check pins them stop-for-stop.
        for pop in [0, 8, 15] {
            assert_eq!(size("places-region", 7.0, pop), 16.0);
            assert_eq!(size("places-subplace", 14.0, pop), 14.0);
        }
    }

    /// The two halves of a data-driven size have to arrive together: a threshold with no
    /// large arm silently never fires, and a large arm with no threshold never applies.
    #[test]
    fn a_data_driven_size_declares_both_halves() {
        for layer in layers() {
            assert_eq!(
                layer.text_size_large.is_some(),
                layer.rank_threshold.is_some(),
                "`{}` declares only half of a data-driven text size",
                layer.id,
            );
            // And the big arm really is the bigger one, or the switch is inverted.
            if let Some(large) = &layer.text_size_large {
                for tenth in 0..=220 {
                    let zoom = tenth as f64 / 10.0;
                    assert!(
                        large.at(zoom) >= layer.text_size.at(zoom),
                        "`{}` draws big places smaller at z{zoom}",
                        layer.id,
                    );
                }
            }
        }
    }
