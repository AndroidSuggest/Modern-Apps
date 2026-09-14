/// Synthesize a basic appearance for annotation types that lack an `/AP` stream.
///
/// Only shapes the file actually specifies are drawn: Square/Circle from `/Rect`,
/// Line from `/L` (with `/LE` endings), Ink from `/InkList`, Polygon/PolyLine
/// from `/Vertices`, the text markup types from `/QuadPoints`, Caret as an
/// insertion wedge, Stamp as its `/Name` wording in a box, and a Widget's field
/// value from `/V` and `/DA` (§12.7.2 Table 218).
///
/// Everything else — Link, FileAttachment, Sound, Movie, Screen, and any
/// of the above missing its defining geometry — draws NOTHING. A crude wrong
/// shape is worse than an absent one: a bare `/Rect` outline is
/// indistinguishable from a Square annotation and asserts a geometry the file
/// never gave. In particular Link must never draw chrome of its own (§12.5.6.5
/// leaves the border to `/Border`, honoured only inside a real `/AP`).
/// `/Rect` inset by HALF the border width, so a stroke laid on the result stays
/// inside the annotation: §8.4.3.2 centres a pen on its path, and a ring laid on
/// `/Rect` itself puts `bw/2` of the border outside. Collapses to the plain rect
/// when the inset would invert it.
fn border_inset_rect(rect: [f64; 4], bw: f64) -> [f64; 4] {
    let r = normalize_rect(rect);
    let i = bw / 2.0;
    let inset = [r[0] + i, r[1] + i, r[2] - i, r[3] - i];
    if inset[2] > inset[0] && inset[3] > inset[1] {
        inset
    } else {
        r
    }
}

/// The rectangle a FreeText annotation's text is laid out in: §12.5.6.6 Table
/// 174's `/RD`, "the numerical differences between ... the Rect entry of the
/// annotation and a rectangle contained within that rectangle. The inner
/// rectangle is where the annotation's text should be displayed."
///
/// Distinct from Table 177's `/RD` in [`shape_rect`], which bounds the SHAPE.
/// Applied to the already border-inset box, since the text sits inside the
/// border. A malformed value is ignored, as for the shape form.
fn free_text_rect(doc: &Document, dict: &lopdf::Dictionary, boxed: [f64; 4]) -> [f64; 4] {
    let rd = match dict
        .get(b"RD")
        .ok()
        .and_then(|o| finite_coords(doc, o))
        .filter(|v| v.len() >= 4 && v.iter().all(|n| *n >= 0.0))
    {
        Some(v) => v,
        None => return boxed,
    };
    let inset = [boxed[0] + rd[0], boxed[1] + rd[3], boxed[2] - rd[2], boxed[3] - rd[1]];
    if inset[2] > inset[0] && inset[3] > inset[1] {
        inset
    } else {
        boxed
    }
}

/// The rectangle a Square/Circle annotation's shape actually occupies.
///
/// §12.5.6.8 Table 177: `/RD` gives "the numerical differences between two
/// rectangles: the `Rect` entry of the annotation and the actual boundaries of
/// the underlying square or circle", as differences in the left, top, right and
/// bottom coordinates. It exists precisely so a shape with a wide border still
/// fits inside `/Rect`, and it was not read anywhere in this crate — so an
/// annotation carrying one was drawn oversized, out to the full `/Rect`.
///
/// Absent `/RD`, the shape is inset by HALF the border width instead: §8.4.3.2
/// centres a stroke on its path, so a path laid on `/Rect` itself puts half the
/// border outside the annotation.
///
/// A malformed `/RD` — negative, non-finite, or wider than the rect — is
/// ignored rather than clamped: it would grow the shape beyond `/Rect` or
/// collapse it, and the plain rect is the better-defined answer.
///
/// This is Table 177's `/RD` and applies ONLY to Square and Circle. §12.5.6.6
/// Table 174 defines a `/RD` for FreeText with different semantics — it insets
/// the TEXT AREA, not the shape boundary — so routing FreeText through here
/// would use a text inset as a border inset.
fn shape_rect(doc: &Document, dict: &lopdf::Dictionary, rect: [f64; 4], bw: f64) -> [f64; 4] {
    let r = normalize_rect(rect);
    let rd = dict
        .get(b"RD")
        .ok()
        .and_then(|o| finite_coords(doc, o))
        .filter(|v| v.len() >= 4 && v.iter().all(|n| *n >= 0.0));
    let [dl, dt, dr, db] = match rd {
        // Table 177 orders the differences left, top, right, bottom. Top is a
        // difference from the TOP edge, so it comes off r[3].
        Some(v) => [v[0], v[1], v[2], v[3]],
        // /RD bounds the shape BORDER AND ALL, so it is never combined with the
        // stroke inset — one or the other.
        None => return border_inset_rect(r, bw),
    };
    let inset = [r[0] + dl, r[1] + db, r[2] - dr, r[3] - dt];
    if inset[2] > inset[0] && inset[3] > inset[1] {
        inset
    } else {
        r
    }
}
