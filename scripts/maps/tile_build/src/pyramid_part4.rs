/// `tile_lines` and `tile_polygons` are the same program with a different
/// [`Accept`], so they share one implementation and differ only in the name they
/// print.
pub fn cli_main(name: &str, accept: Accept, argv: &[String]) -> std::process::ExitCode {
    use std::io::BufRead;
    use std::process::ExitCode;

    let usage = || {
        eprintln!("usage: {name} --geojson IN.geojsonseq --out OUT.pmtiles --layer NAME");
        eprintln!("                  [--minzoom N] [--maxzoom N] [--simplification F]");
        eprintln!("                  [--max-tile-bytes N] [--extent N] [--threads N] [--timing]");
        eprintln!("                  [--stream [--spill-dir DIR] [--buckets N]");
        eprintln!("                            [--bucket-budget-bytes N] [--max-repartition-depth N]]");
        eprintln!();
        eprintln!("  --stream  keep peak memory proportional to the TILE COUNT rather than the");
        eprintln!("            input bytes, by spilling to --spill-dir. Required for a");
        eprintln!("            planet-scale layer; unnecessary for a metro extract. Output is");
        eprintln!("            byte-identical either way.");
    };

    let mut geojson = None;
    let mut out = None;
    let mut layer = None;
    let mut min_zoom = 11u8;
    let mut max_zoom = 14u8;
    let mut simplification = 1.0f64;
    let mut max_tile_bytes = DEFAULT_MAX_TILE_BYTES;
    let mut extent = DEFAULT_EXTENT;
    let mut stream = false;
    let mut timing = false;
    let mut spill_dir: Option<String> = None;
    let mut limits = StreamLimits::default();

    let mut i = 0;
    while i < argv.len() {
        let value = || argv.get(i + 1).cloned();
        match argv[i].as_str() {
            "--geojson" => {
                geojson = value();
                i += 2;
            }
            "--out" => {
                out = value();
                i += 2;
            }
            "--layer" => {
                layer = value();
                i += 2;
            }
            "--stream" => {
                stream = true;
                i += 1;
            }
            "--spill-dir" => {
                spill_dir = value();
                i += 2;
            }
            // A bad numeric value is fatal rather than falling back to the default:
            // silently tiling z11-14 when the caller asked for z0-8 produces an
            // archive that looks fine and is wrong.
            flag @ ("--minzoom" | "--maxzoom" | "--simplification" | "--max-tile-bytes"
            | "--extent" | "--buckets" | "--bucket-budget-bytes"
            | "--max-repartition-depth") => {
                let Some(raw) = value() else {
                    eprintln!("{name}: {flag} needs a value");
                    return ExitCode::from(2);
                };
                let ok = match flag {
                    "--minzoom" => raw.parse().map(|v| min_zoom = v).is_ok(),
                    "--maxzoom" => raw.parse().map(|v| max_zoom = v).is_ok(),
                    "--simplification" => raw.parse().map(|v| simplification = v).is_ok(),
                    "--max-tile-bytes" => raw.parse().map(|v| max_tile_bytes = v).is_ok(),
                    "--extent" => raw.parse().map(|v| extent = v).is_ok(),
                    "--buckets" => raw.parse().map(|v| limits.buckets = v).is_ok(),
                    "--bucket-budget-bytes" => {
                        raw.parse().map(|v| limits.bucket_budget_bytes = v).is_ok()
                    }
                    _ => raw.parse().map(|v| limits.max_repartition_depth = v).is_ok(),
                };
                if !ok {
                    eprintln!("{name}: {flag} wants a number, got '{raw}'");
                    return ExitCode::from(2);
                }
                i += 2;
            }
            "--timing" => {
                timing = true;
                i += 1;
            }
            "--threads" => {
                let Some(raw) = value() else {
                    eprintln!("{name}: --threads needs a value");
                    return ExitCode::from(2);
                };
                match par::parse_threads(&raw) {
                    Ok(n) => par::set_threads(n),
                    Err(e) => {
                        eprintln!("{name}: {e}");
                        return ExitCode::from(2);
                    }
                }
                i += 2;
            }
            "-h" | "--help" => {
                usage();
                return ExitCode::SUCCESS;
            }
            other => {
                eprintln!("{name}: unexpected argument '{other}'");
                usage();
                return ExitCode::from(2);
            }
        }
    }

    let (Some(geojson), Some(out), Some(layer)) = (geojson, out, layer) else {
        eprintln!("{name}: --geojson, --out and --layer are all required");
        usage();
        return ExitCode::from(2);
    };
    if min_zoom > max_zoom {
        eprintln!("{name}: --minzoom {min_zoom} is above --maxzoom {max_zoom}");
        return ExitCode::from(2);
    }
    // Checked before the parse pass rather than after it: a bad bucket count would
    // otherwise fail an hour into a planet run, having already written the whole
    // normalized file.
    if stream && (!limits.buckets.is_power_of_two() || limits.buckets.trailing_zeros() % 2 != 0) {
        eprintln!(
            "{name}: --buckets must be a power of four so a bucket is one quadtree cell, \
             got {}",
            limits.buckets
        );
        return ExitCode::from(2);
    }

    let opts = Options {
        layer: layer.clone(),
        min_zoom,
        max_zoom,
        extent,
        simplification,
        max_tile_bytes,
        // A planet layer spends minutes to hours per zoom, so the operator gets a bar.
        progress: true,
        timing,
    };

    // Read a line at a time rather than slurping the file: a planet geojsonseq is tens
    // of gigabytes and `read_to_string` keeps all of it resident alongside whatever the
    // parse produces, so the two peaks land together for no reason.
    let file = match std::fs::File::open(&geojson) {
        Ok(f) => f,
        Err(e) => {
            eprintln!("{name}: cannot read {geojson}: {e}");
            return ExitCode::FAILURE;
        }
    };
    let mut lines = std::io::BufReader::with_capacity(1 << 20, file).lines();

    if stream {
        let spill_dir = spill_dir.unwrap_or_else(|| format!("{out}.spill"));
        // Both temporaries clean themselves up, so a run that dies mid-planet cannot
        // strand tens of gigabytes in a directory nobody is watching.
        let normalized = crate::spill::NormalizedFile::new(
            std::path::Path::new(&spill_dir).join("features.bin"),
        );
        let mut writer = match crate::spill::NormalizedWriter::create(normalized.path()) {
            Ok(w) => w,
            Err(e) => {
                eprintln!("{name}: {e}");
                return ExitCode::FAILURE;
            }
        };
        let mut skipped = 0usize;
        let mut n = 0usize;
        for line in lines.by_ref() {
            let line = match line {
                Ok(l) => l,
                Err(e) => {
                    eprintln!("{name}: cannot read {geojson}: {e}");
                    return ExitCode::FAILURE;
                }
            };
            n += 1;
            let line = line.trim();
            if line.is_empty() {
                continue;
            }
            match crate::geojson::parse_feature(line) {
                Some(f) if accept.matches(&f.geometry) => {
                    if let Err(e) = writer.push(&f.geometry, &f.props) {
                        eprintln!("{name}: {e}");
                        return ExitCode::FAILURE;
                    }
                }
                _ => {
                    skipped += 1;
                    writer.skip();
                    if skipped <= 5 {
                        eprintln!("{name}: skipping line {n} (not a {})", accept.describe());
                    }
                }
            }
        }
        if skipped > 5 {
            eprintln!("{name}: ... and {} more skipped line(s)", skipped - 5);
        }
        let summary = match writer.finish() {
            Ok(s) => s,
            Err(e) => {
                eprintln!("{name}: {e}");
                return ExitCode::FAILURE;
            }
        };
        if summary.count == 0 {
            eprintln!("{name}: no {} features in {geojson}", accept.describe());
            return ExitCode::FAILURE;
        }
        eprintln!(
            "{name}: normalized {} feature(s) into {}",
            summary.count,
            normalized.path().display()
        );

        let count = summary.count;
        let mut src = match NormalizedSource::open(normalized.path(), summary) {
            Ok(s) => s,
            Err(e) => {
                eprintln!("{name}: {e}");
                return ExitCode::FAILURE;
            }
        };
        let report = match build_archive_to(&out, &spill_dir, &opts, &mut src, &limits) {
            Ok(r) => r,
            Err(e) => {
                eprintln!("{name}: {e}");
                return ExitCode::FAILURE;
            }
        };
        // Only if empty: every bucket set and the normalized file remove themselves, so
        // anything left here is a leftover worth seeing rather than deleting.
        drop(src);
        drop(normalized);
        let _ = std::fs::remove_dir(&spill_dir);

        let size = std::fs::metadata(&out).map(|m| m.len()).unwrap_or(0);
        eprintln!(
            "{name}: wrote {out} ({:.1} MiB): {count} feature(s), layer '{layer}', \
             z{min_zoom}-{max_zoom}",
            size as f64 / (1024.0 * 1024.0),
        );
        report_and_exit(name, &report, max_tile_bytes)
    } else {
        let mut features = Vec::new();
        let mut skipped = 0usize;
        let mut n = 0usize;
        for line in lines.by_ref() {
            let line = match line {
                Ok(l) => l,
                Err(e) => {
                    eprintln!("{name}: cannot read {geojson}: {e}");
                    return ExitCode::FAILURE;
                }
            };
            n += 1;
            let line = line.trim();
            if line.is_empty() {
                continue;
            }
            match crate::geojson::parse_feature(line) {
                Some(f) if accept.matches(&f.geometry) => features.push(Feature {
                    geometry: f.geometry,
                    props: f.props,
                }),
                _ => {
                    skipped += 1;
                    if skipped <= 5 {
                        eprintln!("{name}: skipping line {n} (not a {})", accept.describe());
                    }
                }
            }
        }
        if skipped > 5 {
            eprintln!("{name}: ... and {} more skipped line(s)", skipped - 5);
        }
        if features.is_empty() {
            eprintln!("{name}: no {} features in {geojson}", accept.describe());
            return ExitCode::FAILURE;
        }

        let (bytes, report) = match build_archive(&features, &opts) {
            Ok(v) => v,
            Err(e) => {
                eprintln!("{name}: {e}");
                return ExitCode::FAILURE;
            }
        };
        if let Err(e) = std::fs::write(&out, &bytes) {
            eprintln!("{name}: cannot write {out}: {e}");
            return ExitCode::FAILURE;
        }
        eprintln!(
            "{name}: wrote {out} ({:.1} MiB): {} feature(s), layer '{layer}', \
             z{min_zoom}-{max_zoom}",
            bytes.len() as f64 / (1024.0 * 1024.0),
            features.len(),
        );
        report_and_exit(name, &report, max_tile_bytes)
    }
}

/// The per-zoom report and the drop summary, shared by both paths so a `--stream` build
/// tells the operator exactly what a non-streamed one does.
fn report_and_exit(
    name: &str,
    report: &[ZoomStats],
    max_tile_bytes: usize,
) -> std::process::ExitCode {
    let _ = print_report(report, &mut std::io::stderr());
    let dropped: usize = report.iter().map(|s| s.dropped).sum();
    if dropped > 0 {
        eprintln!(
            "{name}: {dropped} feature placement(s) dropped by the {max_tile_bytes}-byte \
             per-tile budget"
        );
    }
    std::process::ExitCode::SUCCESS
}
