//! Flat-style tests, part 1: the loader, colours, ramps and line strokes.
//!
//! Split from [`paint`]'s test module so each file stays small.

use super::paint::{FLAT, MIN_HALF_WIDTH_PX, Ramp, Stroke, parse, parse_hex};
use super::paint_extra2::{ZOOMS, authored_layer, basemap, dash_drawn, find, line_layers};
use crate::style::{LayerKind, Toggle, layers};
use serde_json::Value as Json;
use tilecodec::mamaps::body::FLAG_IS_BRIDGE;

    // --- the loader ---
    // --- the loader --------------------------------------------------------

    #[test]
    fn the_vendored_flat_style_parses() {
        let style = parse(FLAT).expect("style/basemap.flat.json should parse");
        // Counted exactly, and split basemap from optional: the file *is* the layer set,
        // so a layer appearing or disappearing is a decision rather than an incidental
        // restyle. Splitting the count means adding an optional layer touches one number
        // that says what it is, instead of nudging a basemap total that then no longer
        // states what the basemap is.
        //
        // Basemap: 24 fills, 24 lines and 7 symbols. The 24 lines are the 12
        // surface/link layers, 10 bridge layers (5 casings + 5 fills), and the two app-only
        // carriageway layers that draw a road's surface and its lane markings at z16+ —
        // `roads-carriageway` and `junction-connector`, which continues the same surface
        // through an intersection: the authored
        // `is_bridge` pass the flat file used to drop entirely, which is what hid the Bay
        // Bridge and the Golden Gate (task 8). The 7 symbols are the 4-deep places hierarchy
        // (country/region/locality/subplace) plus the 3 curved line labels WS-E added
        // (roads-label-major, roads-label-minor, waterway-label).
        //
        // Optional: 1 transit line, and 6 POI symbols — one per colour group of the
        // reference `pois` layer's `text-color` `case` on `kind`. Six and not seven: the
        // `case` has a `#e2dfda` default arm, but the same layer's `filter` admits exactly
        // the 36 kinds the six groups list between them, so the default is unreachable.
        // A seventh unfiltered layer would not be a fallback, it would draw all 36 POIs a
        // second time in the wrong colour —
        // `the_poi_colour_groups_partition_the_kinds_the_reference_admits` pins the
        // arithmetic that makes the omission safe.
        let count = |kind: LayerKind, optional: bool| {
            style
                .layers
                .iter()
                .filter(|l| l.kind == kind && l.toggle.is_some() == optional)
                .count()
        };
        assert_eq!(
            (count(LayerKind::Fill, false), count(LayerKind::Line, false), count(LayerKind::Symbol, false)),
            (24, 24, 7),
            "the basemap layer set",
        );
        assert_eq!(
            (count(LayerKind::Fill, true), count(LayerKind::Line, true), count(LayerKind::Symbol, true)),
            (0, 1, 6),
            "the optional layer set",
        );
        assert_eq!(style.background, (0xFF80DEEA, 0xFF0D1B2A));
    }

    /// The six POI layers have to cover the reference filter exactly: every kind once, and
    /// no kind twice.
    ///
    /// A kind missing from all six is a POI the reference draws and we do not. A kind in
    /// two is a POI drawn twice, in whichever colour comes last — and since the layers are
    /// one per colour, that is a silent recolouring rather than a visible double image.
    /// This is also what licenses leaving the `case`'s default arm out entirely.
    #[test]
    fn the_poi_colour_groups_partition_the_kinds_the_reference_admits() {
        let root = basemap();
        let authored = authored_layer(&root, "pois");
        // `["all", ["in", ["get","kind"], ["literal", [...]]], [">=", ["zoom"], ...]]`
        let mut admitted: Vec<String> = Vec::new();
        let filter = authored.get("filter").and_then(Json::as_array).expect("a filter");
        for clause in filter {
            let Some(clause) = clause.as_array() else { continue };
            if clause.first().and_then(Json::as_str) != Some("in") {
                continue;
            }
            let Some(literal) = clause.get(2).and_then(Json::as_array) else { continue };
            let Some(names) = literal.get(1).and_then(Json::as_array) else { continue };
            admitted.extend(names.iter().filter_map(Json::as_str).map(str::to_string));
        }
        assert_eq!(admitted.len(), 36, "the reference admits 36 kinds");

        let mut drawn: Vec<String> = layers()
            .iter()
            .filter(|l| l.toggle == Some(Toggle::Poi))
            .flat_map(|l| l.kinds.iter().cloned())
            .collect();
        let before = drawn.len();
        drawn.sort_unstable();
        drawn.dedup();
        assert_eq!(before, drawn.len(), "a kind is claimed by two POI layers");
        // Four kinds are ours, not the reference's. The `maps` app has always offered Gas,
        // Hotels and ATM chips and the reference basemap draws none of those three, so the
        // chips could only ever have filtered a set that did not contain their subject. Stated
        // as an explicit extension rather than folded into the count, so this test still fails
        // if a kind is added to a POI layer by accident.
        const LOCAL: &[&str] = &["atm", "bank", "fuel", "hotel"];
        let (local, reference): (Vec<String>, Vec<String>) =
            drawn.into_iter().partition(|kind| LOCAL.contains(&kind.as_str()));
        assert_eq!(local, LOCAL, "the local POI extension is exactly these four kinds");
        admitted.sort_unstable();
        assert_eq!(reference, admitted, "the POI layers do not cover the reference filter");
    }

    #[test]
    fn a_malformed_style_fails_to_load_rather_than_loading_partly() {
        let cases = [
            (r##"{}"##, "no background"),
            (r##"{"background":{"light":"#fff","dark":"#000"},"layers":[]}"##, "a bad colour"),
            (
                r##"{"background":{"light":"#ffffff","dark":"#000000"},
                    "layers":[{"id":"x","authored":"x","source":"s","type":"blob",
                               "light":"#ffffff","dark":"#000000"}]}"##,
                "an unknown type",
            ),
            (
                r##"{"background":{"light":"#ffffff","dark":"#000000"},
                    "layers":[{"id":"x","authored":"x","source":"s","type":"fill",
                               "light":"#ffffff","dark":"#000000",
                               "width":{"interpolate":"exponential","stops":[[1,2]]}}]}"##,
                "an exponential ramp with no base",
            ),
            (
                r##"{"background":{"light":"#ffffff","dark":"#000000"},
                    "layers":[{"id":"x","authored":"x","source":"s","type":"fill",
                               "light":"#ffffff","dark":"#000000",
                               "width":{"interpolate":"linear","stops":[[5,1],[3,0]]}}]}"##,
                "descending stops",
            ),
            (
                r##"{"background":{"light":"#ffffff","dark":"#000000"},
                    "layers":[{"id":"x","authored":"x","source":"s","type":"fill",
                               "light":"#ffffff","dark":"#000000","gapwidth":3}]}"##,
                "a misspelled property",
            ),
            (
                r##"{"background":{"light":"#ffffff","dark":"#000000"},
                    "layers":[{"id":"x","authored":"x","source":"s","type":"fill",
                               "light":"#ffffff","dark":"#000000","minzoom":14.0}]}"##,
                "a fractional minzoom",
            ),
            (
                r##"{"background":{"light":"#ffffff","dark":"#000000"},
                    "layers":[{"id":"x","authored":"x","source":"s","type":"fill",
                               "light":"#ffffff","dark":"#000000","maxzoom":40}]}"##,
                "a zoom past the renderer's own maximum",
            ),
        ];
        for (source, what) in cases {
            assert!(parse(source).is_err(), "{what} should not load");
        }
    }

    #[test]
    fn a_hex_colour_parses_and_anything_else_does_not() {
        assert_eq!(parse_hex("#80deea"), Some(0xFF80DEEA));
        assert_eq!(parse_hex("#E2DFDA"), Some(0xFFE2DFDA), "case does not matter");
        assert_eq!(parse_hex("#11223380"), Some(0x80112233));
        for source in ["", "#", "#abc", "#12345", "#gg0000", "cornflowerblue", "rgb(1,2,3)"] {
            assert_eq!(parse_hex(source), None, "{source:?} must not parse");
        }
        // `from_str_radix` would take these; a colour column must not.
        assert_eq!(parse_hex("#+12345"), None);
        assert_eq!(parse_hex("#12 345"), None);
    }

    // --- ramps -------------------------------------------------------------

    #[test]
    fn a_ramp_clamps_outside_its_stops_and_interpolates_inside() {
        let ramp = Ramp { base: 1.0, stops: vec![(5.0, 1.0), (7.0, 0.0)] };
        assert_eq!(ramp.at(0.0), 1.0, "clamped below the first stop");
        assert_eq!(ramp.at(5.0), 1.0);
        assert_eq!(ramp.at(6.0), 0.5, "halfway, linearly");
        assert_eq!(ramp.at(7.0), 0.0);
        assert_eq!(ramp.at(22.0), 0.0, "clamped above the last");
        assert_eq!(ramp.peak(), 1.0);
    }

    #[test]
    fn an_exponential_ramp_grows_slowly_at_first_as_the_spec_defines_it() {
        let ramp = Ramp { base: 1.6, stops: vec![(0.0, 0.0), (2.0, 1.0)] };
        // (1.6^1 - 1) / (1.6^2 - 1) = 0.6 / 1.56.
        assert!((ramp.at(1.0) - 0.3846).abs() < 1e-4, "{}", ramp.at(1.0));
        assert!(ramp.at(1.0) < 0.5, "an exponential curve lags a linear one");
        assert_eq!(Ramp { base: 1.0, stops: vec![(0.0, 0.0), (2.0, 1.0)] }.at(1.0), 0.5);
    }

    #[test]
    fn a_constant_is_a_ramp_with_one_stop() {
        let ramp = Ramp::constant(0.7);
        for tenth in 0..=220 {
            assert_eq!(ramp.at(tenth as f64 / 10.0), 0.7);
        }
        assert_eq!(ramp.peak(), 0.7);
    }

    #[test]
    fn a_ramp_picks_the_right_segment_of_several() {
        let ramp =
            Ramp { base: 1.0, stops: vec![(0.0, 0.0), (10.0, 10.0), (20.0, 0.0)] };
        assert_eq!(ramp.at(5.0), 5.0, "the rising segment");
        assert_eq!(ramp.at(15.0), 5.0, "the falling one");
        assert_eq!(ramp.at(10.0), 10.0, "the shared stop");
        assert_eq!(ramp.peak(), 10.0, "the peak is not the last stop");
    }

    // --- lines -------------------------------------------------------------

    #[test]
    fn every_line_layer_draws_something_somewhere_in_the_zoom_range() {
        for layer in line_layers() {
            let drawn = ZOOMS.into_iter().any(|zoom| layer.stroke(zoom as f64).visible());
            assert!(drawn, "`{}` is invisible at every zoom", layer.id);
        }
    }

    /// The defect a constant width had: a street-level width applied at world level.
    #[test]
    fn a_highway_at_world_zoom_is_a_small_fraction_of_its_width_at_street_zoom() {
        let highway = find("roads-highway");
        let (world, street) = (highway.stroke(4.0).width_dp, highway.stroke(14.0).width_dp);
        assert!(world > 0.0 && street > 0.0, "world {world}, street {street}");
        assert!(
            world < street * 0.15,
            "a highway is {world} Dp at z4 against {street} Dp at z14, which is not a \
             continent-scale road drawn thin",
        );
    }

    /// The invariant the constants violated: 6 Dp of highway plus its casing at z4 is the
    /// 15-25px ribbon that merged neighbouring roads into sheets.
    #[test]
    fn no_line_layer_is_more_than_a_couple_of_dp_wide_below_street_zoom() {
        for layer in line_layers() {
            for tenth in 0..80 {
                let zoom = tenth as f64 / 10.0;
                let stroke = layer.stroke(zoom);
                let total = stroke.width_dp * 2.0 + stroke.gap_width_dp * 2.0;
                assert!(total <= 10.0, "`{}` is {total} Dp across at z{zoom}", layer.id);
            }
        }
    }

    #[test]
    fn width_grows_continuously_with_zoom_rather_than_stepping_per_level() {
        // Across an integer boundary the ramp is inside one segment, so a width quantised to
        // the tile zoom would repeat itself here.
        let highway = find("roads-highway");
        let widths: Vec<f32> =
            (0..=10).map(|step| highway.stroke(13.5 + step as f64 / 10.0).width_dp).collect();
        for pair in widths.windows(2) {
            assert!(pair[1] > pair[0], "width stepped rather than grew: {widths:?}");
        }
        assert!(widths[4] < widths[5] && widths[5] < widths[6], "{widths:?}");
    }

    #[test]
    fn a_highway_grows_monotonically_across_the_whole_zoom_range() {
        let highway = find("roads-highway");
        let mut previous = -1.0;
        for tenth in 0..=220 {
            let width = highway.stroke(tenth as f64 / 10.0).width_dp;
            assert!(width >= previous, "width fell at z{}", tenth as f64 / 10.0);
            previous = width;
        }
    }

    /// Root cause of roads appearing five levels early: `roads-highway` has no `min_zoom`, so
    /// its gate has to be the ramp reaching zero.
    #[test]
    fn an_ungated_road_layer_is_still_not_drawn_at_world_zoom() {
        let highway = find("roads-highway");
        assert_eq!(highway.min_zoom, 0, "this asserts the ramp gates, not `min_zoom`");
        for tenth in 0..=30 {
            let zoom = tenth as f64 / 10.0;
            assert!(!highway.stroke(zoom).visible(), "drawn at z{zoom}");
        }
    }

    /// `gapped()` decides, at tessellation time, whether a layer emits one centred band or two
    /// offset ones, and the pushed gap width is discarded if it emits one. So the two have to
    /// agree: the flag is the ramp's peak, and nothing else may set it.
    #[test]
    fn a_layer_is_gapped_exactly_when_its_gap_ramp_is_ever_non_zero() {
        for layer in layers() {
            let ever = (0..=220).any(|tenth| layer.stroke(tenth as f64 / 10.0).gap_width_dp > 0.0);
            assert_eq!(layer.gapped(), ever, "`{}` disagrees with its gap ramp", layer.id);
        }
        assert!(find("roads-major-casing").gapped());
        assert!(find("roads-link-casing").gapped(), "a link casing is still a casing");
        assert!(!find("roads-major").gapped());
        assert!(!find("roads-link").gapped(), "a link fill is a single centred stroke");
        assert!(!find("earth").gapped());
    }

    #[test]
    fn a_stroke_with_a_gap_but_no_width_is_not_visible() {
        assert!(!Stroke { width_dp: 0.0, gap_width_dp: 8.0 }.visible());
        assert!(Stroke { width_dp: 0.5, gap_width_dp: 0.0 }.visible());
        assert!(!Stroke::NONE.visible());
    }

    /// Whatever the ramps say, a road has to be wide enough to see once the map is a street
    /// map — otherwise this trades one visible defect for another.
    #[test]
    fn roads_are_still_drawn_at_street_zoom() {
        for id in ["roads-highway", "roads-major", "roads-minor", "roads-link"] {
            let width = find(id).stroke(16.0).width_dp;
            assert!(width >= 1.0, "`{id}` is {width} Dp at z16");
        }
    }

    /// A bridge is drawn by the bridge layers and by nothing else: every surface road
    /// layer carries `forbid_flags: ["bridge"]`. So wherever a surface class draws, its
    /// bridge counterpart has to draw too, or the road is chopped at every crossing.
    ///
    /// This is what hid the Golden Gate and the Bay Bridge below z12, and with them every
    /// highway overpass in the network - `roads-bridges-highway` had a `minzoom` of 12
    /// that the authored style does not give it.
    #[test]
    fn a_highway_bridge_draws_wherever_a_surface_highway_does() {
        let (surface, bridge) = (find("roads-highway"), find("roads-bridges-highway"));
        assert!(
            surface.forbid_flags & FLAG_IS_BRIDGE != 0,
            "the surface layer must exclude bridges, or this test proves nothing",
        );
        for tenth in 0..=220 {
            let zoom = tenth as f64 / 10.0;
            if !surface.stroke(zoom).visible() || !surface.draws_at(zoom.floor() as u8) {
                continue;
            }
            assert!(
                bridge.draws_at(zoom.floor() as u8) && bridge.stroke(zoom).visible(),
                "a highway draws at z{zoom} but its bridges do not",
            );
        }
    }
    #[test]
    fn a_sub_pixel_stroke_keeps_its_true_width() {
        let hair = Stroke { width_dp: 0.18, gap_width_dp: 0.0 };
        let (half_width, _) = hair.half_px(3.0);
        assert!(
            (half_width - 0.27).abs() < 1e-6,
            "0.18 Dp at density 3 is 0.27 px of half-width, got {half_width}",
        );
        assert!(half_width < MIN_HALF_WIDTH_PX, "and it is under the shader's floor");
        // A stroke already wider than a pixel is unaffected either way.
        let solid = Stroke { width_dp: 4.0, gap_width_dp: 3.0 };
        assert_eq!(solid.half_px(3.0), (6.0, 4.5));
        // Density still scales it.
        assert_eq!(Stroke { width_dp: 0.4, gap_width_dp: 0.0 }.half_px(1.0).0, 0.2);
        assert_eq!(Stroke { width_dp: 0.4, gap_width_dp: 0.0 }.half_px(3.0).0, 0.6);
    }

    /// Both line shaders hardcode the floor because GLSL cannot include a Rust
    /// constant. A silent divergence would put the geometry and the coverage term on
    /// different widths, which shows up as roads that are too faint or too hard-edged
    /// — subtle enough to survive review.
    #[test]
    fn the_shader_width_floor_matches_this_constant() {
        let declared = format!("const float MIN_HALF_WIDTH_PX = {MIN_HALF_WIDTH_PX:.1};");
        for (name, source) in [
            ("line.vert", include_str!("../../shaders/line.vert")),
            ("line.frag", include_str!("../../shaders/line.frag")),
        ] {
            assert!(source.contains(&declared), "{name} does not declare `{declared}`");
        }
    }

    /// The gap is deliberately not floored: two bands a sub-pixel apart read as one band, which
    /// is correct, whereas forcing them apart would widen a road the style wanted narrow.
    #[test]
    fn a_sub_pixel_gap_is_left_alone() {
        assert_eq!(Stroke { width_dp: 2.0, gap_width_dp: 0.1 }.half_px(3.0).1, 0.15);
    }
    /// The no-regression guarantee: a static road (`morph.y = 0`) or a stopped clock makes the
    /// phase 0, and the dash then has to be exactly what the pre-WS-B shader drew.
    #[test]
    fn a_static_dash_is_byte_identical_with_a_zero_phase() {
        // The un-phased test the shader ran before WS-B.
        let unphased = |d: f32, on: f32, off: f32| d.rem_euclid(on + off) <= on;
        let (on, off) = (6.0, 6.0);
        for i in 0..480 {
            let d = i as f32 * 0.25;
            assert_eq!(
                dash_drawn(d, on, off, 0.0),
                unphased(d, on, off),
                "a zero phase must reproduce the old dash at distance {d}",
            );
        }
        // A solid line (non-positive gap) is untouched too.
        for i in 0..480 {
            let d = i as f32 * 0.25;
            assert!(dash_drawn(d, 2.0, 0.0, 12.0), "a [2, 0] line stays solid under any phase");
        }
    }

    /// And the animation actually moves: a whole-period phase is a no-op, a half-period phase
    /// inverts the pattern. If this ever stops differing the dash has frozen.
    #[test]
    fn a_travelling_dash_shifts_with_the_phase() {
        let (on, off) = (6.0, 6.0);
        let period = on + off;
        let mut differed = false;
        for i in 0..480 {
            let d = i as f32 * 0.25;
            assert_eq!(
                dash_drawn(d, on, off, period),
                dash_drawn(d, on, off, 0.0),
                "a whole-period phase lands back on the same pattern",
            );
            if dash_drawn(d, on, off, period / 2.0) != dash_drawn(d, on, off, 0.0) {
                differed = true;
            }
        }
        assert!(differed, "a half-period phase must move the dash");
    }

    /// The phase is gated on both the clock and the per-draw speed slot, so a future edit
    /// cannot animate static lines by accident. `line.frag` hardcodes the expression because
    /// GLSL cannot include the Rust push layout.
    #[test]
    fn the_dash_phase_is_gated_on_the_clock_and_the_speed_slot() {
        let frag = include_str!("../../shaders/line.frag");
        assert!(
            frag.contains("push.misc.w * push.morph.y"),
            "line.frag must derive the phase from the clock times the per-draw speed slot",
        );
        assert!(
            frag.contains("mod(inDistancePx - phase, period)"),
            "line.frag must subtract the phase inside the dash modulo",
        );
    }
