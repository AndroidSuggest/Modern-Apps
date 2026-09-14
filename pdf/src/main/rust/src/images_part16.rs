#[cfg(test)]
mod indirect_and_adobe_tests {
    use super::*;

    /// §7.3.10: any dictionary value may be an indirect reference. `num` does not
    /// follow one, so every numeric read on an image dictionary has to deref first.
    /// `/Width` and `/Height` are the ones that delete the image outright — they feed a
    /// `let ... else { return None }`.
    #[test]
    fn indirect_width_and_height_still_produce_an_image() {
        let mut doc = Document::with_version("1.7");
        let w_ref = doc.add_object(Object::Integer(2));
        let h_ref = doc.add_object(Object::Integer(2));
        let dict = dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => Object::Reference(w_ref),
            "Height" => Object::Reference(h_ref),
            "BitsPerComponent" => 8,
            "ColorSpace" => "DeviceGray",
        };
        let img = extract_image(&doc, &Stream::new(dict, vec![0x20u8; 4]), 0xFF00_0000, &HashMap::new())
            .expect("an indirect /Width and /Height must not delete the image");
        assert_eq!((img.w, img.h), (2, 2));
        assert_eq!(&img.data[0..3], &[0x20, 0x20, 0x20]);
    }

    /// An indirect `/BitsPerComponent` used to read as the 8 default. At a true 1 bpc
    /// that makes the computed stride 8x too wide, which trips the one-row guard and
    /// drops the image.
    #[test]
    fn indirect_bits_per_component_is_not_read_as_the_default() {
        let mut doc = Document::with_version("1.7");
        let bpc = doc.add_object(Object::Integer(1));
        let dict = dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 16, "Height" => 2,
            "BitsPerComponent" => Object::Reference(bpc),
            "ColorSpace" => "DeviceGray",
        };
        // 16 px at 1 bpc is 2 bytes per row, 4 bytes total. Read as 8 bpc the guard
        // would demand 16 bytes for one row and drop the image.
        let img = extract_image(&doc, &Stream::new(dict, vec![0b1010_1010u8; 4]), 0xFF00_0000, &HashMap::new())
            .expect("an indirect /BitsPerComponent must not be read as 8");
        assert_eq!((img.w, img.h), (16, 2));
        assert_eq!(&img.data[0..3], &[255, 255, 255], "sample 1 at 1 bpc is white");
        assert_eq!(&img.data[4..7], &[0, 0, 0], "sample 0 at 1 bpc is black");
    }

    /// F14: an APP14 "Adobe" segment is written by every Photoshop JPEG export, so
    /// keying the "the platform cannot decode this" branch on its mere presence sent
    /// ordinary RGB photographs down the Rust decode path and gave up the passthrough.
    /// Only YCCK (transform 2) is a transform the platform cannot undo.
    #[test]
    fn an_adobe_marked_rgb_jpeg_keeps_the_passthrough() {
        let doc = Document::with_version("1.7");
        // Transform 1 = YCbCr, the marker Photoshop writes for an ordinary RGB save.
        let mut jpeg: Vec<u8> = vec![0xFF, 0xD8];
        jpeg.extend_from_slice(&[0xFF, 0xEE, 0x00, 0x0E]);
        jpeg.extend_from_slice(b"Adobe");
        jpeg.extend_from_slice(&[0x00, 0x64, 0x00, 0x00, 0x00, 0x00, 0x01]);
        // SOF0 declaring 3 components.
        jpeg.extend_from_slice(&[0xFF, 0xC0, 0x00, 0x11, 0x08, 0x00, 0x08, 0x00, 0x08, 0x03]);
        jpeg.extend_from_slice(&[0u8; 9]);
        jpeg.extend_from_slice(&[0xFF, 0xDA, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00]);
        jpeg.extend_from_slice(&[0xFF, 0xD9]);
        assert_eq!(jpeg_num_components(&jpeg), Some(3));
        assert_eq!(jpeg_adobe_transform(&jpeg), Some(1), "Photoshop RGB writes transform 1");

        let dict = dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 8, "Height" => 8, "BitsPerComponent" => 8,
            "ColorSpace" => "DeviceRGB",
            "Filter" => "DCTDecode",
        };
        let img = extract_image(&doc, &Stream::new(dict, jpeg), 0xFF00_0000, &HashMap::new())
            .expect("an Adobe-marked RGB JPEG must not be dropped");
        assert_eq!(
            img.format, 1,
            "a 3-component Adobe JPEG belongs on the platform passthrough, not the Rust path"
        );
    }

    /// F14, second half: an undecodable JPEG was dropped ONLY when it carried a mask,
    /// while the identical bytes without one were passed through. The mask arm now
    /// falls through to the same passthrough — visible but unmasked, matching what
    /// `decode_mask_stream_gray` already does when it cannot decode a mask.
    #[test]
    fn an_undecodable_jpeg_with_an_smask_passes_through_instead_of_vanishing() {
        let mut doc = Document::with_version("1.7");
        // Markers only, no entropy data: jpeg-decoder cannot produce pixels from this.
        let mut jpeg: Vec<u8> = vec![0xFF, 0xD8];
        jpeg.extend_from_slice(&[0xFF, 0xC0, 0x00, 0x11, 0x08, 0x00, 0x08, 0x00, 0x08, 0x03]);
        jpeg.extend_from_slice(&[0u8; 9]);
        jpeg.extend_from_slice(&[0xFF, 0xD9]);
        assert!(decode_jpeg_rgba_decoded(&jpeg, false).is_none(), "fixture must be undecodable");

        let base = dictionary! {
            "Type" => "XObject", "Subtype" => "Image",
            "Width" => 8, "Height" => 8, "BitsPerComponent" => 8,
            "ColorSpace" => "DeviceRGB",
            "Filter" => "DCTDecode",
        };
        let no_mask = extract_image(&doc, &Stream::new(base.clone(), jpeg.clone()), 0xFF00_0000, &HashMap::new())
            .expect("without a mask these bytes already passed through");
        assert_eq!(no_mask.format, 1);

        let smask = doc.add_object(Object::Stream(Stream::new(
            dictionary! {
                "Type" => "XObject", "Subtype" => "Image",
                "Width" => 8, "Height" => 8, "BitsPerComponent" => 8,
                "ColorSpace" => "DeviceGray",
            },
            vec![255u8; 64],
        )));
        let mut masked = base;
        masked.set("SMask", Object::Reference(smask));
        let with_mask = extract_image(&doc, &Stream::new(masked, jpeg), 0xFF00_0000, &HashMap::new())
            .expect("carrying an /SMask must not be the reason an image disappears");
        assert_eq!(
            with_mask.format, 1,
            "same bytes, same passthrough — the /SMask is lost, but the image is not"
        );
    }
}
