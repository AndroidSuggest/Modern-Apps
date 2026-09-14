#[cfg(test)]
mod tests {
    use super::*;

    /// Type 1 charstring number encoding (Adobe Type 1 Font Format 6.2).
    fn n(v: i32) -> Vec<u8> {
        if (-107..=107).contains(&v) {
            vec![(v + 139) as u8]
        } else if (108..=1131).contains(&v) {
            let d = v - 108;
            vec![(247 + d / 256) as u8, (d % 256) as u8]
        } else if (-1131..=-108).contains(&v) {
            let d = -v - 108;
            vec![(251 + d / 256) as u8, (d % 256) as u8]
        } else {
            let b = v.to_be_bytes();
            vec![255, b[0], b[1], b[2], b[3]]
        }
    }

    #[test]
    fn subrs_array_size_is_not_taken_from_the_file() {
        // `/Subrs <n> array` and each `dup <i>` index size a heap allocation
        // straight from untrusted bytes. Unclamped, this asks for ~48 GB of empty
        // Vecs before a single charstring is read.
        let dec = b"/lenIV 0 def\n/Subrs 2000000000 array\ndup 0 3 RD abc NP\ndup 1999999999 3 RD def NP\nND\n/CharStrings 1 dict dup begin\n/A 3 RD xyz ND\nend";
        let (subrs, glyphs) = parse_private(dec);
        assert!(subrs.len() <= 65536, "subr table sized from the file: {}", subrs.len());
        assert_eq!(subrs[0].len(), 3, "the in-range subr is still stored");
        assert!(glyphs.contains_key("A"), "CharStrings still parse after the cap");
    }

    #[test]
    fn flex_emits_one_contour_and_leaves_the_current_point_correct() {
        // OtherSubrs 1/2/0 flex (Adobe Type 1 Font Format 8.3). The seven
        // rmovetos are reference points, NOT contour starts, and OtherSubr 0
        // returns the end point for the trailing `pop pop setcurrentpoint`.
        // Getting either wrong restarts the contour seven times and corrupts the
        // current point, damaging every segment drawn after the flex.
        let mut cs: Vec<u8> = Vec::new();
        cs.extend(n(0));
        cs.extend(n(500));
        cs.push(13); // hsbw
        cs.extend(n(0));
        cs.extend(n(0));
        cs.push(21); // rmoveto -> contour starts at (0, 0)
        cs.extend(n(0));
        cs.extend(n(1));
        cs.extend([12, 16]); // 0 1 callothersubr -> begin flex
        for (dx, dy) in [(50, 50), (10, 10), (10, 10), (10, -10), (10, -10), (10, 10), (10, 10)] {
            cs.extend(n(dx));
            cs.extend(n(dy));
            cs.push(21); // rmoveto -> reference point
            cs.extend(n(0));
            cs.extend(n(2));
            cs.extend([12, 16]); // 0 2 callothersubr -> collect
        }
        cs.extend(n(50)); // flex depth
        cs.extend(n(110)); // end x
        cs.extend(n(70)); // end y
        cs.extend(n(3));
        cs.extend(n(0));
        cs.extend([12, 16]); // 3 0 callothersubr -> end flex
        cs.extend([12, 17, 12, 17, 12, 33]); // pop pop setcurrentpoint
        cs.extend(n(10));
        cs.extend(n(0));
        cs.push(5); // rlineto -> (120, 70), proves the pen survived the flex
        cs.push(14); // endchar

        let mut cb = ContourBuilder::new();
        run_charstring(&cs, &[], &HashMap::new(), &mut cb);
        let contours = cb.finish();

        assert_eq!(contours.len(), 1, "flex must not start new contours");
        let c = &contours[0];
        assert_eq!(c.first().copied(), Some((0.0, 0.0)));
        // `endchar` closes the contour, and `ContourBuilder` represents closure as
        // a duplicated first point (the convention `interpret.rs`'s `h` uses), so
        // the pen's final position is the point BEFORE that closing point.
        assert_eq!(c.last().copied(), Some((0.0, 0.0)), "contour is explicitly closed");
        let pen = c[c.len() - 2];
        assert!(
            (pen.0 - 120.0).abs() < 1e-6 && (pen.1 - 70.0).abs() < 1e-6,
            "trailing rlineto ended at {pen:?}, expected (120, 70)"
        );
        // The join between the two cubics is the flex midpoint, reference point 3.
        assert!(
            c.iter().any(|&(x, y)| (x - 80.0).abs() < 1e-6 && (y - 60.0).abs() < 1e-6),
            "first flex curve must end at (80, 60)"
        );
    }

    #[test]
    fn a_dup_inside_charstrings_is_not_loaded_as_a_subr() {
        // The Subrs array ends at /CharStrings. A `dup <n> <len> RD` sequence
        // after that point belongs to the glyph dict (or is a coincidence in a
        // glyph's binary); loading it would both install a bogus subr and let a
        // file-supplied index grow the table.
        let dec = b"/lenIV 0 def\n/Subrs 2 array\ndup 0 3 RD abc NP\ndup 1 3 RD def NP\nND\n/CharStrings 1 dict dup begin\n/A 3 RD xyz ND\ndup 900 3 RD zzz NP\nend";
        let (subrs, glyphs) = parse_private(dec);
        assert_eq!(subrs.len(), 2, "the table must not grow past the declared count");
        // Charstring bytes are eexec-decrypted, so only the lengths are stable.
        assert_eq!(subrs[0].len(), 3);
        assert_eq!(subrs[1].len(), 3);
        assert_eq!(glyphs.get("A").map(|v| v.len()), Some(3));
    }
}
