use crate::*;
    use jni::objects::{JByteArray, JClass, JFloatArray, JString};
    use jni::sys::{jbyteArray, jboolean, jfloat, jint, jlong, jstring};
    use jni::JNIEnv;

    /// `PdfNative.openDocument(byte[]) -> long`. Returns a non-zero handle, or
    /// 0 on parse failure / encrypted document.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_openDocument<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        data: JByteArray<'local>,
    ) -> jlong {
        let bytes = match env.convert_byte_array(&data) {
            Ok(b) => b,
            Err(_) => return 0,
        };
        open_document(&bytes) as jlong
    }

    /// `PdfNative.openDocumentWithPassword(byte[], String) -> long`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_openDocumentWithPassword<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        data: JByteArray<'local>,
        password: JString<'local>,
    ) -> jlong {
        let bytes = match env.convert_byte_array(&data) {
            Ok(b) => b,
            Err(_) => return 0,
        };
        let pw = jstr(&mut env, &password);
        open_document_pw(&bytes, pw.as_bytes()) as jlong
    }

    /// `PdfNative.pdfPasswordState(byte[]) -> int` (0 none, 1 needs pw, 2 unsupported).
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_pdfPasswordState<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        data: JByteArray<'local>,
    ) -> jint {
        let bytes = match env.convert_byte_array(&data) {
            Ok(b) => b,
            Err(_) => return 0,
        };
        pdf_password_state(&bytes)
    }

    /// `PdfNative.saveEncrypted(long, String, String) -> byte[]`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_saveEncrypted<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        user_pw: JString<'local>,
        owner_pw: JString<'local>,
    ) -> jbyteArray {
        let u = jstr(&mut env, &user_pw);
        let o = jstr(&mut env, &owner_pw);
        bytes_or_null(&env, save_encrypted(handle as i64, u.as_bytes(), o.as_bytes()))
    }

    /// `PdfNative.signCms(byte[], String) -> byte[]`. Detached CMS/PKCS#7
    /// signature (fresh self-signed RSA-2048, SHA-256 with RSA) over `content`,
    /// or null on failure. Replaces the Bouncy Castle path on the Kotlin side.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_signCms<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        content: JByteArray<'local>,
        name: JString<'local>,
    ) -> jbyteArray {
        let data = match env.convert_byte_array(&content) {
            Ok(b) => b,
            Err(_) => return std::ptr::null_mut(),
        };
        let nm = jstr(&mut env, &name);
        bytes_or_null(&env, crate::signing::sign_cms(&data, &nm))
    }

    /// `PdfNative.getPageCount(long) -> int`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_getPageCount<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
    ) -> jint {
        page_count(handle as i64)
    }

    /// `PdfNative.renderPage(long, int) -> byte[]`. Serialized primitives, or
    /// `null` on error.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_renderPage<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        index: jint,
    ) -> jbyteArray {
        let null = std::ptr::null_mut();
        let buf = match render_page(handle as i64, index) {
            Some(b) => b,
            None => return null,
        };
        match env.byte_array_from_slice(&buf) {
            Ok(arr) => arr.into_raw(),
            Err(_) => null,
        }
    }

    /// `PdfNative.closeDocument(long)`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_closeDocument<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
    ) {
        close_document(handle as i64);
    }

    /// `PdfNative.createEmptyDocument() -> long`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_createEmptyDocument<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
    ) -> jlong {
        create_empty_document()
    }

    /// `PdfNative.appendPdf(long, byte[]) -> int` (pages added).
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_appendPdf<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        data: JByteArray<'local>,
    ) -> jint {
        let bytes = match env.convert_byte_array(&data) {
            Ok(b) => b,
            Err(_) => return 0,
        };
        append_pdf(handle as i64, &bytes)
    }

    /// `PdfNative.appendImagePage(long, byte[], int, int) -> int`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_appendImagePage<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        jpeg: JByteArray<'local>,
        w: jint,
        h: jint,
    ) -> jint {
        let bytes = match env.convert_byte_array(&jpeg) {
            Ok(b) => b,
            Err(_) => return 0,
        };
        append_image_page(handle as i64, &bytes, w as u32, h as u32)
    }

    /// `PdfNative.movePage(long, int, int) -> boolean`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_movePage<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        from: jint,
        to: jint,
    ) -> jboolean {
        move_page(handle as i64, from.max(0) as usize, to.max(0) as usize) as jboolean
    }

    /// `PdfNative.removePage(long, int) -> boolean`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_removePage<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        index: jint,
    ) -> jboolean {
        remove_page(handle as i64, index.max(0) as usize) as jboolean
    }

    /// `PdfNative.rotatePage(long, int, int) -> boolean`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_rotatePage<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        index: jint,
        delta: jint,
    ) -> jboolean {
        rotate_page(handle as i64, index, delta) as jboolean
    }

    /// `PdfNative.extractPage(long, int) -> byte[]`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_extractPage<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        index: jint,
    ) -> jbyteArray {
        bytes_or_null(&env, extract_page(handle as i64, index))
    }

    fn bytes_or_null<'local>(env: &JNIEnv<'local>, data: Option<Vec<u8>>) -> jbyteArray {
        let null = std::ptr::null_mut();
        match data {
            Some(b) => match env.byte_array_from_slice(&b) {
                Ok(arr) => arr.into_raw(),
                Err(_) => null,
            },
            None => null,
        }
    }

    fn jstr(env: &mut JNIEnv, s: &JString) -> String {
        env.get_string(s).map(|s| s.into()).unwrap_or_default()
    }

    /// `PdfNative.listAnnotations(long, int) -> byte[]`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_listAnnotations<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
    ) -> jbyteArray {
        bytes_or_null(&env, list_annotations(handle as i64, page))
    }

    /// `PdfNative.listFormFields(long, int) -> byte[]`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_listFormFields<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
    ) -> jbyteArray {
        bytes_or_null(&env, list_form_fields(handle as i64, page))
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_listLinks<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
    ) -> jbyteArray {
        bytes_or_null(&env, list_links(handle as i64, page))
    }

    #[allow(clippy::too_many_arguments)]
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addTextAnnotation<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
        x0: jfloat,
        y0: jfloat,
        x1: jfloat,
        y1: jfloat,
        argb: jint,
        size: jfloat,
        text: JString<'local>,
    ) -> jlong {
        let t = jstr(&mut env, &text);
        add_free_text(
            handle as i64,
            page,
            [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
            argb as u32,
            size as f64,
            &t,
        )
        .unwrap_or(0)
    }

    #[allow(clippy::too_many_arguments)]
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addHighlight<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
        x0: jfloat,
        y0: jfloat,
        x1: jfloat,
        y1: jfloat,
        argb: jint,
    ) -> jlong {
        add_highlight(
            handle as i64,
            page,
            [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
            argb as u32,
        )
        .unwrap_or(0)
    }

    #[allow(clippy::too_many_arguments)]
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addTextMarkup<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
        x0: jfloat,
        y0: jfloat,
        x1: jfloat,
        y1: jfloat,
        argb: jint,
        kind: jint,
    ) -> jlong {
        add_text_markup(
            handle as i64,
            page,
            [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
            argb as u32,
            kind,
        )
        .unwrap_or(0)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addNote<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
        x: jfloat,
        y: jfloat,
        argb: jint,
        text: JString<'local>,
    ) -> jlong {
        let t = jstr(&mut env, &text);
        add_note(handle as i64, page, x as f64, y as f64, argb as u32, &t).unwrap_or(0)
    }

    #[allow(clippy::too_many_arguments)]
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addCallout<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
        ax: jfloat,
        ay: jfloat,
        bx: jfloat,
        by: jfloat,
        argb: jint,
        size: jfloat,
        text: JString<'local>,
    ) -> jlong {
        let t = jstr(&mut env, &text);
        add_callout(
            handle as i64,
            page,
            ax as f64,
            ay as f64,
            bx as f64,
            by as f64,
            argb as u32,
            size as f64,
            &t,
        )
        .unwrap_or(0)
    }

    #[allow(clippy::too_many_arguments)]
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addRectAnnotation<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
        x0: jfloat,
        y0: jfloat,
        x1: jfloat,
        y1: jfloat,
        argb: jint,
        line_width: jfloat,
        fill: jboolean,
    ) -> jlong {
        add_square(
            handle as i64,
            page,
            [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
            argb as u32,
            line_width as f64,
            fill != 0,
        )
        .unwrap_or(0)
    }

    #[allow(clippy::too_many_arguments)]
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addCircleAnnotation<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
        x0: jfloat,
        y0: jfloat,
        x1: jfloat,
        y1: jfloat,
        argb: jint,
        line_width: jfloat,
        fill: jboolean,
    ) -> jlong {
        add_circle(
            handle as i64,
            page,
            [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
            argb as u32,
            line_width as f64,
            fill != 0,
        )
        .unwrap_or(0)
    }

    /// `PdfNative.addPolyAnnotation(long, int, int argb, float width, bool fill, bool closed, float[] pts)`.
    #[allow(clippy::too_many_arguments)]
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addPolyAnnotation<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
        argb: jint,
        line_width: jfloat,
        fill: jboolean,
        closed: jboolean,
        pts: JFloatArray<'local>,
    ) -> jlong {
        let len = env.get_array_length(&pts).unwrap_or(0) as usize;
        let mut buf = vec![0f32; len];
        if env.get_float_array_region(&pts, 0, &mut buf).is_err() {
            return 0;
        }
        add_poly(
            handle as i64,
            page,
            &buf,
            argb as u32,
            line_width as f64,
            fill != 0,
            closed != 0,
        )
        .unwrap_or(0)
    }

    /// `PdfNative.addInkAnnotation(long, int, int argb, float width, float[] pts)`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addInkAnnotation<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
        argb: jint,
        line_width: jfloat,
        pts: JFloatArray<'local>,
    ) -> jlong {
        let len = env.get_array_length(&pts).unwrap_or(0) as usize;
        let mut buf = vec![0f32; len];
        if env.get_float_array_region(&pts, 0, &mut buf).is_err() {
            return 0;
        }
        add_ink(handle as i64, page, argb as u32, line_width as f64, &buf).unwrap_or(0)
    }

    /// `PdfNative.addImageStamp(long, int, rect, imgW, imgH, byte[] jpeg)`.
    #[allow(clippy::too_many_arguments)]
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addImageStamp<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
        x0: jfloat,
        y0: jfloat,
        x1: jfloat,
        y1: jfloat,
        img_w: jint,
        img_h: jint,
        jpeg: JByteArray<'local>,
    ) -> jlong {
        let bytes = match env.convert_byte_array(&jpeg) {
            Ok(b) => b,
            Err(_) => return 0,
        };
        add_stamp(
            handle as i64,
            page,
            [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
            img_w as u32,
            img_h as u32,
            &bytes,
        )
        .unwrap_or(0)
    }

    #[allow(clippy::too_many_arguments)]
    #[allow(clippy::too_many_arguments)]
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_updateAnnotationRect<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
        annot_id: jlong,
        x0: jfloat,
        y0: jfloat,
        x1: jfloat,
        y1: jfloat,
    ) -> jboolean {
        update_annotation_rect(
            handle as i64,
            page,
            annot_id,
            [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
        ) as jboolean
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_updateTextAnnotation<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        annot_id: jlong,
        text: JString<'local>,
    ) -> jboolean {
        let t = jstr(&mut env, &text);
        update_free_text(handle as i64, annot_id, &t) as jboolean
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_deleteAnnotation<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
        annot_id: jlong,
    ) -> jboolean {
        delete_annotation(handle as i64, page, annot_id) as jboolean
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_detachAnnotation<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
        annot_id: jlong,
    ) -> jboolean {
        detach_annotation(handle as i64, page, annot_id) as jboolean
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_reattachAnnotation<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
        annot_id: jlong,
    ) -> jboolean {
        reattach_annotation(handle as i64, page, annot_id) as jboolean
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_duplicateAnnotation<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
        annot_id: jlong,
        dx: jfloat,
        dy: jfloat,
    ) -> jlong {
        duplicate_annotation(handle as i64, page, annot_id, dx as f64, dy as f64)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_setTextField<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        widget_id: jlong,
        value: JString<'local>,
    ) -> jboolean {
        let v = jstr(&mut env, &value);
        set_text_field(handle as i64, widget_id, &v) as jboolean
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_setCheckbox<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        widget_id: jlong,
        on: jboolean,
    ) -> jboolean {
        set_checkbox(handle as i64, widget_id, on != 0) as jboolean
    }

    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_setChoiceField<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        widget_id: jlong,
        value: JString<'local>,
    ) -> jboolean {
        let v = jstr(&mut env, &value);
        set_choice_field(handle as i64, widget_id, &v) as jboolean
    }

    /// `PdfNative.saveDocument(long) -> byte[]`. Serialized modified PDF.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_saveDocument<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
    ) -> jbyteArray {
        bytes_or_null(&env, save_document(handle as i64))
    }

    /// `PdfNative.prepareSignature(long, String, int) -> byte[]`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_prepareSignature<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        name: JString<'local>,
        contents_bytes: jint,
    ) -> jbyteArray {
        let n = jstr(&mut env, &name);
        bytes_or_null(&env, prepare_signature(handle as i64, &n, contents_bytes.max(0) as usize))
    }

    /// `PdfNative.saveCompressed(long) -> byte[]`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_saveCompressed<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
    ) -> jbyteArray {
        bytes_or_null(&env, save_compressed(handle as i64))
    }

    /// `PdfNative.flattenDocument(long) -> boolean`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_flattenDocument<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
    ) -> jboolean {
        flatten_document(handle as i64) as jboolean
    }

    /// `PdfNative.applyRedactions(long) -> boolean`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_applyRedactions<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
    ) -> jboolean {
        apply_redactions(handle as i64) as jboolean
    }

    /// `PdfNative.hasRedactions(long) -> boolean`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_hasRedactions<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
    ) -> jboolean {
        has_redactions(handle as i64) as jboolean
    }

    /// `PdfNative.addRedaction(long, int, f,f,f,f) -> long`.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addRedaction<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        page: jint,
        x0: jfloat,
        y0: jfloat,
        x1: jfloat,
        y1: jfloat,
    ) -> jlong {
        add_redaction(handle as i64, page, [x0 as f64, y0 as f64, x1 as f64, y1 as f64]).unwrap_or(0)
    }

    /// `PdfNative.extractText(long) -> String` (null on failure).
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_extractText<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
    ) -> jstring {
        match document_text(handle as i64).and_then(|s| env.new_string(s).ok()) {
            Some(s) => s.into_raw(),
            None => std::ptr::null_mut(),
        }
    }

    /// `PdfNative.listOutline(long) -> byte[]`. Serialized document outline.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_listOutline<'local>(
        env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
    ) -> jbyteArray {
        bytes_or_null(&env, list_outline(handle as i64))
    }

    /// `PdfNative.searchDocument(long, String) -> byte[]`. Serialized matches.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_searchDocument<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        query: JString<'local>,
    ) -> jbyteArray {
        let q = jstr(&mut env, &query);
        bytes_or_null(&env, search_document(handle as i64, &q))
    }

    /// `PdfNative.searchDocumentCaseSensitive(long, String) -> byte[]`. Phase 7 toggle.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_searchDocumentCaseSensitive<'local>(
        mut env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
        query: JString<'local>,
    ) -> jbyteArray {
        let q = jstr(&mut env, &query);
        bytes_or_null(&env, search_document_case_sensitive(handle as i64, &q))
    }

    /// `PdfNative.buildSearchIndex(long)`. Prebuilds the text index so the first
    /// search is instant; safe to call on a background thread.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_buildSearchIndex<'local>(
        _env: JNIEnv<'local>,
        _class: JClass<'local>,
        handle: jlong,
    ) {
        let _ = ensure_index(handle as i64);
    }
