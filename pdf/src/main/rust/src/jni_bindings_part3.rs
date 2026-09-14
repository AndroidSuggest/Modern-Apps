#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_detachAnnotation<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    annot_id: jlong,
) -> jboolean {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        detach_annotation(handle, page, annot_id) as jboolean
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_reattachAnnotation<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    annot_id: jlong,
) -> jboolean {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        reattach_annotation(handle, page, annot_id) as jboolean
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_duplicateAnnotation<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    annot_id: jlong,
    dx: jfloat,
    dy: jfloat,
) -> jlong {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        duplicate_annotation(handle, page, annot_id, dx as f64, dy as f64)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_setTextField<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    widget_id: jlong,
    value: JString<'local>,
) -> jboolean {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        set_text_field_inner(&mut env, handle, widget_id, value)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_setCheckbox<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    widget_id: jlong,
    on: jboolean,
) -> jboolean {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        set_checkbox(handle, widget_id, on != 0) as jboolean
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_setChoiceField<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    widget_id: jlong,
    value: JString<'local>,
) -> jboolean {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        set_choice_field_inner(&mut env, handle, widget_id, value)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

/// `PdfNative.saveDocument(long) -> byte[]`. Serialized modified PDF.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_saveDocument<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| save_document_inner(&mut env, handle))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            let _ = env.throw_new(
                "java/lang/RuntimeException",
                "Native panic in saveDocument",
            );
            std::ptr::null_mut()
        }
    }
}

/// `PdfNative.saveCompressed(long) -> byte[]`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_saveCompressed<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| save_compressed_inner(&mut env, handle))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            std::ptr::null_mut()
        }
    }
}

/// `PdfNative.flattenDocument(long) -> boolean`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_flattenDocument<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jboolean {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| flatten_document(handle) as jboolean)) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

/// `PdfNative.applyRedactions(long) -> boolean`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_applyRedactions<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jboolean {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| apply_redactions(handle) as jboolean)) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

/// `PdfNative.hasRedactions(long) -> boolean`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_hasRedactions<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jboolean {
    match catch_unwind(AssertUnwindSafe(|| has_redactions(handle) as jboolean)) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

/// `PdfNative.addRedaction(long, int, f,f,f,f) -> long`.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_addRedaction<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page: jint,
    x0: jfloat,
    y0: jfloat,
    x1: jfloat,
    y1: jfloat,
) -> jlong {
    let _invalidate = InvalidateSearchIndex(handle);
    match catch_unwind(AssertUnwindSafe(|| {
        add_redaction(
            handle,
            page,
            [x0 as f64, y0 as f64, x1 as f64, y1 as f64],
        )
        .unwrap_or(0)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            0
        }
    }
}

/// `PdfNative.extractText(long) -> String` (null on failure).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_extractText<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jstring {
    match catch_unwind(AssertUnwindSafe(|| extract_text_inner(&mut env, handle))) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            std::ptr::null_mut()
        }
    }
}

/// `PdfNative.listOutline(long) -> byte[]`. Serialized document outline.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_listOutline<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| {
        list_data_inner(&mut env, || list_outline(handle))
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            std::ptr::null_mut()
        }
    }
}

/// `PdfNative.searchDocument(long, String) -> byte[]`. Serialized matches.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_searchDocument<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    query: JString<'local>,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| {
        search_document_inner_fn(&mut env, handle, query)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            std::ptr::null_mut()
        }
    }
}

/// `PdfNative.searchDocumentCaseSensitive(long, String) -> byte[]`. Phase 7 toggle.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_searchDocumentCaseSensitive<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    query: JString<'local>,
) -> jbyteArray {
    match catch_unwind(AssertUnwindSafe(|| {
        search_document_cs_inner(&mut env, handle, query)
    })) {
        Ok(v) => v,
        Err(_) => {
            let _ = env.exception_clear();
            std::ptr::null_mut()
        }
    }
}

/// `PdfNative.buildSearchIndex(long)`. Prebuilds the text index so the first
/// search is instant; safe to call on a background thread.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_pdf_util_PdfNative_buildSearchIndex<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    let res = catch_unwind(AssertUnwindSafe(|| {
        let _ = ensure_index(handle);
    }));
    if res.is_err() {
        let _ = env.exception_clear();
    }
}
