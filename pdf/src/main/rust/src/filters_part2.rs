#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn ccitt_rows_the_decoder_never_reached_come_back_white() {
        // §7.4.6 Table 11: /Rows (or /Height) fixes the scan-line count, and a real
        // fax stream is routinely truncated. The rows the decoder never produced used
        // to keep the zero the buffer was allocated with, which under the /BlackIs1
        // DEFAULT is BLACK - a solid black band across the bottom of the page, and
        // alpha 0 (the base image gone) when the same stream is a soft mask.
        //
        // A fax page's background is white, so that is what an unproduced row has to be.
        let mut enc = fax::encoder::Encoder::new(fax::VecWriter::new());
        let row = (0..8).map(|_| fax::Color::White);
        enc.encode_line(row, 8).expect("VecWriter is infallible");
        let data = enc.finish().expect("infallible").finish();

        // Three rows declared, one row of payload.
        let params = CcittParams { k: -1, columns: 8, rows: 3, ..CcittParams::default() };
        let packed = decode_ccitt(&data, 8, 3, &params).expect("one good row must still decode");
        assert_eq!(packed.len(), 3, "one byte per 8-column row, three rows");
        // /BlackIs1 false => a 1 bit is WHITE, so an unreached row must be all ones.
        assert_eq!(packed[1], 0xFF, "row 1 was never decoded and must read as white");
        assert_eq!(packed[2], 0xFF, "row 2 likewise");

        // /BlackIs1 true reverses which bit means white, so the background flips too.
        let params = CcittParams { black_is1: true, ..params };
        let packed = decode_ccitt(&data, 8, 3, &params).expect("decodes");
        assert_eq!(packed[1], 0x00, "with /BlackIs1 true a 0 bit is white");
        assert_eq!(packed[2], 0x00);
    }

    #[test]
    fn ccitt_pels_are_written_not_or_ed_into_the_white_background() {
        // The background fill only works if a decoded pel can CLEAR a bit as well as
        // set one; an OR-only fill would leave every pel white.
        let mut enc = fax::encoder::Encoder::new(fax::VecWriter::new());
        // 1010... so a bug that writes only one polarity is visible in one byte.
        let row: Vec<fax::Color> = (0..8)
            .map(|x| if x % 2 == 0 { fax::Color::Black } else { fax::Color::White })
            .collect();
        enc.encode_line(row.iter().copied(), 8).expect("infallible");
        let data = enc.finish().expect("infallible").finish();
        let params = CcittParams { k: -1, columns: 8, rows: 1, ..CcittParams::default() };
        let packed = decode_ccitt(&data, 8, 1, &params).expect("decodes");
        // /BlackIs1 false: black pel => 0 bit, white pel => 1 bit.
        assert_eq!(packed[0], 0b0101_0101, "alternating pels must survive the fill");
    }

    #[test] fn ascii_hex_basic() {
        let out = decode_ascii_hex(b"48656C6C6F>");
        assert_eq!(out, b"Hello");
        let out2 = decode_ascii_hex(b"4");
        assert_eq!(out2, vec![0x40]);
    }
    #[test] fn ascii85_z() {
        let out = decode_ascii85(b"z~>").unwrap();
        assert_eq!(out, vec![0,0,0,0]);
    }
    #[test] fn runlength() {
        let data = vec![0, 0xAB, 128];
        assert_eq!(decode_runlength(&data), vec![0xAB]);
        let data2 = vec![254, 0xFF, 128];
        assert_eq!(decode_runlength(&data2), vec![0xFF,0xFF,0xFF]);
    }
    #[test] fn normalize_filters() {
        assert_eq!(normalize_filter_name("FlateDecode"), FilterKind::Flate);
        assert_eq!(normalize_filter_name("/Fl"), FilterKind::Flate);
        assert_eq!(normalize_filter_name("ASCII85DECODE"), FilterKind::Ascii85);
        assert_eq!(normalize_filter_name("DCTDecode"), FilterKind::Dct);
        assert_eq!(normalize_filter_name("JBIG2Decode"), FilterKind::Jbig2);
        assert_eq!(normalize_filter_name("CCITTFaxDecode"), FilterKind::Ccitt);
    }
    #[test] fn tiff_predictor2_horizontal() {
        // Two rows, 3 columns, 1 color, 8bpc. Encoded as left-differences.
        // Row0 original [10, 20, 30] -> diffs [10, 10, 10].
        // Row1 original [ 5,  5,  5] -> diffs [ 5,  0,  0].
        let encoded = vec![10u8, 10, 10, 5, 0, 0];
        let out = apply_tiff_predictor2(encoded, 3, 1, 8);
        assert_eq!(out, vec![10, 20, 30, 5, 5, 5]);
    }
    #[test] fn tiff_predictor2_16bit() {
        // One row, 2 columns, 1 color, 16bpc big-endian.
        // Original samples [0x0102, 0x0305] -> diffs [0x0102, 0x0203].
        let encoded = vec![0x01, 0x02, 0x02, 0x03];
        let out = apply_tiff_predictor2(encoded, 2, 1, 16);
        assert_eq!(out, vec![0x01, 0x02, 0x03, 0x05]);
    }
    #[test] fn tiff_predictor2_4bit() {
        // One row, 4 columns, 1 color, 4bpc. Original nibbles [1,3,6,7].
        // Diffs [1,2,3,1] -> packed 0x12, 0x31.
        let encoded = vec![0x12, 0x31];
        let out = apply_tiff_predictor2(encoded, 4, 1, 4);
        assert_eq!(out, vec![0x13, 0x67]);
    }
    #[test] fn tiff_predictor2_1bit() {
        // One row, 8 columns, 1 color, 1bpc. Original bits 1 0 1 1 0 0 1 0.
        // Left diffs (xor-like add mod 2): b[0]=1; b[i]=orig[i]-orig[i-1] mod2
        // orig=10110010 -> diffs: 1, 1, 1, 0, 1, 0, 1, 1 = 0b11101011 = 0xEB.
        let encoded = vec![0xEBu8];
        let out = apply_tiff_predictor2(encoded, 8, 1, 1);
        assert_eq!(out, vec![0b10110010]);
    }

    #[test] fn png_predictor_rgb_8bpc_sub() {
        // 2 rows, 2 columns, 3 colors, 8bpc => row_bytes 6, bpp 3.
        // Filter 1 (Sub) row0: [10,20,30, 5,5,5] -> [10,20,30, 15,25,35]
        // Filter 2 (Up)  row1: [1,1,1, 1,1,1]    -> previous row + 1
        let data = vec![1, 10, 20, 30, 5, 5, 5, 2, 1, 1, 1, 1, 1, 1];
        let out = apply_png_predictor(data, 2, 3, 8);
        assert_eq!(out, vec![10, 20, 30, 15, 25, 35, 11, 21, 31, 16, 26, 36]);
    }

    #[test] fn png_predictor_1bpc_row_length() {
        // 1 color, 1bpc, 17 columns => ceil(17/8) = 3 bytes per row, NOT 17.
        // Using `bpp * columns` (lopdf's decode_frame) would demand 17 bytes and fail.
        // Two rows, filter 0 (None), so the payload passes through untouched.
        let data = vec![0, 0xAA, 0xBB, 0x80, 0, 0x11, 0x22, 0x00];
        let out = apply_png_predictor(data, 17, 1, 1);
        assert_eq!(out, vec![0xAA, 0xBB, 0x80, 0x11, 0x22, 0x00]);
    }

    #[test] fn png_predictor_keeps_truncated_final_row() {
        // row_bytes 3; the second row supplies only 2 of its 3 bytes. The first row must
        // survive rather than the whole frame being discarded.
        let data = vec![0, 1, 2, 3, 0, 4, 5];
        let out = apply_png_predictor(data, 3, 1, 8);
        assert_eq!(out.len(), 6);
        assert_eq!(&out[..3], &[1, 2, 3]);
        assert_eq!(&out[3..], &[4, 5, 0]);
    }

    #[test] fn flate_returns_partial_output_when_truncated() {
        use flate2::write::ZlibEncoder;
        use std::io::Write;
        let payload: Vec<u8> = (0..4096u32).map(|i| (i % 251) as u8).collect();
        let mut e = ZlibEncoder::new(Vec::new(), flate2::Compression::default());
        e.write_all(&payload).expect("encode");
        let full = e.finish().expect("finish");
        // Lop off the tail: the stream is now corrupt but most of it still inflates.
        let truncated = &full[..full.len() - 8];
        let out = decode_flate(truncated).expect("partial output, not None");
        assert!(!out.is_empty(), "partial inflate must not be discarded");
        assert_eq!(out, payload[..out.len()], "partial output must be a valid prefix");
    }

    #[test] fn ascii85_overflow_is_an_error_not_a_panic() {
        // "s8W-" is exactly u32::MAX/85*85, so the next digit overflows the ADD.
        assert!(decode_ascii85(b"s8W-\"~>").is_err());
        // The maximal legal group must still decode.
        assert_eq!(decode_ascii85(b"s8W-!~>").unwrap(), vec![0xFF, 0xFF, 0xFF, 0xFF]);
    }

    #[test] fn ascii85_skips_postscript_opener() {
        assert_eq!(decode_ascii85(b"<~z~>").unwrap(), vec![0, 0, 0, 0]);
    }

    #[test] fn lzw_truncated_stream_keeps_decoded_prefix() {
        // 'A' 'B' with no EOD code: 9-bit codes 65, 66 then the stream just stops.
        // 0 0100 0001 0 0100 0010 -> 0x20, 0x90, 0x88 (trailing bits are padding).
        let out = decode_lzw(&[0x20, 0x90, 0x88], true).expect("partial output");
        assert_eq!(&out[..2], b"AB");
    }

    #[test] fn ccitt_is_left_encoded_for_the_image_layer() {
        // decode_stream_chain must NOT decode CCITT: the image layer owns it because only
        // it knows the real /Width and /Height.
        let doc = Document::new();
        let specs = vec![(FilterKind::Ccitt, None)];
        let raw = vec![0x26, 0xA0, 0x00, 0x11];
        let out = decode_stream_chain(raw.clone(), &specs, &doc).expect("passthrough");
        assert_eq!(out, raw, "CCITT bytes must reach the image layer untouched");
    }

    #[test] fn inline_image_f_abbreviation_is_a_filter() {
        // §8.9.7 Table 93: an inline image spells /Filter as /F and /DecodeParms as /DP.
        // Without this the filter chain came back empty and the still-ENCODED bytes were
        // used as image samples.
        let doc = Document::new();
        let mut dict = Dictionary::new();
        dict.set("W", Object::Integer(4));
        dict.set("H", Object::Integer(4));
        dict.set("F", Object::Name(b"AHx".to_vec()));
        let specs = filter_specs_from_dict(&doc, &dict);
        assert_eq!(specs.len(), 1);
        assert_eq!(specs[0].0, FilterKind::AsciiHex);

        // /DP carries the parameters under the same abbreviation scheme.
        let mut dict = Dictionary::new();
        dict.set("F", Object::Name(b"Fl".to_vec()));
        let mut dp = Dictionary::new();
        dp.set("Predictor", Object::Integer(12));
        dp.set("Columns", Object::Integer(4));
        dict.set("DP", Object::Dictionary(dp));
        let specs = filter_specs_from_dict(&doc, &dict);
        assert_eq!(specs[0].0, FilterKind::Flate);
        let parms = specs[0].1.as_ref().expect("/DP must reach the Flate filter");
        assert_eq!(parms.get(b"Predictor").unwrap(), &Object::Integer(12));
    }

    #[test] fn stream_f_file_specification_is_not_treated_as_a_filter() {
        // In a regular STREAM dictionary /F is a file specification (§7.3.8.2), not a
        // filter. Only the Name/Array shapes a filter can have may be accepted, or an
        // external-file reference would be misread as a filter name.
        let doc = Document::new();
        let mut dict = Dictionary::new();
        dict.set("F", Object::String(b"/tmp/data.bin".to_vec(), lopdf::StringFormat::Literal));
        assert!(filter_specs_from_dict(&doc, &dict).is_empty());

        let mut dict = Dictionary::new();
        let mut fs = Dictionary::new();
        fs.set("Type", Object::Name(b"Filespec".to_vec()));
        dict.set("F", Object::Dictionary(fs));
        assert!(filter_specs_from_dict(&doc, &dict).is_empty());
    }

    #[test] fn explicit_filter_still_wins_over_f() {
        // /F is only consulted when /Filter is absent, so no existing behaviour changes.
        let doc = Document::new();
        let mut dict = Dictionary::new();
        dict.set("Filter", Object::Name(b"FlateDecode".to_vec()));
        dict.set("F", Object::Name(b"AHx".to_vec()));
        let specs = filter_specs_from_dict(&doc, &dict);
        assert_eq!(specs.len(), 1);
        assert_eq!(specs[0].0, FilterKind::Flate);
    }

    /// Writes MSB-first codes, which is the bit order §7.4.3 specifies for LZWDecode.
    struct BitWriter {
        out: Vec<u8>,
        acc: u32,
        bits: u32,
    }
    impl BitWriter {
        fn new() -> Self {
            BitWriter { out: Vec::new(), acc: 0, bits: 0 }
        }
        fn put(&mut self, code: u32, width: u32) {
            self.acc = (self.acc << width) | code;
            self.bits += width;
            while self.bits >= 8 {
                self.bits -= 8;
                self.out.push((self.acc >> self.bits) as u8);
            }
        }
        fn finish(mut self) -> Vec<u8> {
            if self.bits > 0 {
                self.out.push((self.acc << (8 - self.bits)) as u8);
            }
            self.out
        }
    }

    #[test]
    fn lzw_early_change_bumps_the_code_width_one_entry_early() {
        // §7.4.3 Table 8: /EarlyChange 1 (the DEFAULT) makes the code length grow one
        // code sooner than strictly necessary. Getting the boundary wrong desynchronizes
        // the bit stream from that point on, so everything after it is noise - the
        // classic "second half of the image is garbage" symptom.
        //
        // Codes 0..=253 are single-byte literals. The first adds no dictionary entry
        // (there is no previous string), each later one adds exactly one, so after code
        // 253 the dictionary holds 258 + 253 = 511 entries. With EarlyChange the width
        // must ALREADY be 10 bits for the next code; without it, it is still 9.
        let literals: Vec<u32> = (0..=253u32).collect();
        let mut early = BitWriter::new();
        for &c in &literals {
            early.put(c, 9);
        }
        early.put(100, 10);
        early.put(257, 10); // EOD
        let out = decode_lzw(&early.finish(), true).expect("decodes");
        let mut want: Vec<u8> = (0..=253u8).collect();
        want.push(100);
        assert_eq!(out, want, "EarlyChange 1 must switch to 10 bits at 511 entries");

        // /EarlyChange 0 keeps 9 bits until the 512th entry, so the same 254 literals
        // are followed by one more 9-bit code before the width changes.
        let mut late = BitWriter::new();
        for &c in &literals {
            late.put(c, 9);
        }
        late.put(100, 9);
        late.put(257, 10); // the 512th entry exists by now, so EOD is 10 bits
        let out = decode_lzw(&late.finish(), false).expect("decodes");
        assert_eq!(out, want, "EarlyChange 0 must switch to 10 bits at 512 entries");
    }

    #[test]
    fn lzw_kwkwk_case() {
        // §7.4.3: a code one past the end of the table means "previous string plus its
        // own first character". Emitting `A` then the not-yet-defined code 258 must
        // produce `AA`, not abandon the stream.
        let mut w = BitWriter::new();
        w.put(b'A' as u32, 9);
        w.put(258, 9);
        w.put(257, 9);
        assert_eq!(decode_lzw(&w.finish(), true).expect("decodes"), b"AAA");
    }

    #[test]
    fn lzw_output_is_bounded_by_the_decode_ceiling() {
        // §7.4.3 bounds neither the expansion ratio nor the output length, so the only
        // limit on a hostile stream is the one imposed here. Everything decoded up to
        // the ceiling is still returned.
        let mut w = BitWriter::new();
        for _ in 0..64 {
            w.put(b'A' as u32, 9);
        }
        let out = lzw_decode_std(&w.finish(), true, 4).expect("partial output");
        assert_eq!(out, b"AAAA", "decoding must stop at the ceiling, not at the data");
    }

    #[test]
    fn ccitt_row_count_comes_from_the_height_not_the_compressed_length() {
        // §7.4.6 Table 11: with /Rows absent the scan-line count is the image's /Height.
        // The old code used `max(data.len()*8/columns, h)`. That estimate exceeds `h`
        // only when the encoded payload is LARGER than the raster it encodes, so a
        // normally-compressed fax never reaches it and the two agree - which is why the
        // bug was invisible at fixture scale. On a padded or corrupt stream it does
        // exceed `h`, and past the 20000-row guard `decode_ccitt` returned None and the
        // image was dropped ENTIRELY rather than mis-sized.
        //
        // The numbers below are therefore deliberately pathological, not realistic: they
        // have to be, because that is the only shape of input the two expressions
        // disagree on. This witnesses spec conformance and the no-total-loss property,
        // NOT a scenario a well-formed file reaches.
        let params = CcittParams { k: -1, columns: 8, ..CcittParams::default() };
        // 8 columns, 4 rows => 1 byte per row. A payload far longer than the geometry
        // would previously have driven `rows` from its own length.
        let data = vec![0u8; 40_000];
        let packed = decode_ccitt(&data, 8, 4, &params);
        if let Some(p) = packed {
            assert_eq!(p.len(), 4, "one byte per 8-column row, /Height rows");
        }
        // The case that used to disappear: columns * (len*8/columns) exceeds the 20000
        // row guard, so `decode_ccitt` returned None and the image vanished.
        let big = vec![0u8; 300_000];
        let params = CcittParams { k: -1, columns: 100, ..CcittParams::default() };
        assert!(
            decode_ccitt(&big, 100, 50, &params).is_some(),
            "a 50-row image must not be refused because its data is long"
        );
    }

    #[test]
    fn runlength_output_is_bounded_by_the_decode_ceiling() {
        // §7.4.5 expands a 2-byte run record into up to 128 bytes, so a stream at the
        // 200 MB input ceiling reaches ~25 GB unbounded.
        let data = [0x81u8, 0xAA].repeat(64); // 64 runs of 128 bytes = 8192 bytes
        let out = decode_runlength_limited(&data, 5);
        assert_eq!(out.len(), 128, "one run past the ceiling, then stop");
        assert!(out.iter().all(|&b| b == 0xAA));
        // Unlimited decoding of the same input is unchanged.
        assert_eq!(decode_runlength(&data).len(), 8192);
    }
}
