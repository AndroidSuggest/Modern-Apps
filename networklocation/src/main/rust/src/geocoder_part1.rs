impl Reader {
    fn open(fd: i32, offset: i64) -> Option<Reader> {
        let dupfd = unsafe { libc::dup(fd) };
        if dupfd < 0 {
            return None;
        }
        let file = unsafe { File::from_raw_fd(dupfd) };
        Reader::from_src(Src { file, base: offset as u64 })
    }

    /// Open a `.geodb` straight from a filesystem path (base offset 0). Test-only: the
    /// production path receives an APK asset fd + offset via `open`.
    #[cfg(test)]
    fn open_path<P: AsRef<std::path::Path>>(path: P) -> Option<Reader> {
        let file = File::open(path).ok()?;
        Reader::from_src(Src { file, base: 0 })
    }

    fn from_src(src: Src) -> Option<Reader> {
        if src.rd_u32(0)? != MAGIC || src.rd_u32(4)? != VERSION {
            return None;
        }
        let n = src.rd_i32(8)?;
        let mut cursor: u64 = 12;

        let mut dicts = Vec::with_capacity(DICTS);
        for _ in 0..DICTS {
            dicts.push(decode_dict(&sect_bytes(&src, &mut cursor)?)?);
        }

        let delta = [true, true, false, false, false, false, false, false, false, false];
        let mut cols = Vec::with_capacity(COLUMNS);
        for i in 0..COLUMNS {
            let off = sect_body(&src, &mut cursor)?;
            cols.push(Column::new(&src, off, delta[i])?);
        }

        let grid = sect_bytes(&src, &mut cursor)?;
        let count = be32(&grid[0..4]) as usize;
        let mut cell_ids = Vec::with_capacity(count);
        let mut cell_starts = Vec::with_capacity(count);
        let mut g = 4usize;
        for _ in 0..count {
            cell_ids.push(be64(&grid[g..g + 8]));
            cell_starts.push(be32(&grid[g + 8..g + 12]) as i32);
            g += 12;
        }

        let fwd_off = sect_body(&src, &mut cursor)?;
        let fwd = Column::new(&src, fwd_off, true)?;

        let nm_name_off = sect_body(&src, &mut cursor)?;
        let nm_name = Column::new(&src, nm_name_off, true)?;
        let nm_rec_off = sect_body(&src, &mut cursor)?;
        let nm_rec = Column::new(&src, nm_rec_off, false)?;

        Some(Reader { src, n, dicts, cell_ids, cell_starts, cols, fwd, nm_name, nm_rec })
    }

    fn grid_index(&self, cell: i64) -> i32 {
        let mut lo = 0i32;
        let mut hi = self.cell_ids.len() as i32 - 1;
        while lo <= hi {
            let mid = (lo + hi) >> 1;
            let v = self.cell_ids[mid as usize];
            if v < cell {
                lo = mid + 1;
            } else if v > cell {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        -1
    }

    fn dict_index(&self, field: usize, key: &str) -> i32 {
        let dict = &self.dicts[field];
        let mut lo = 0i32;
        let mut hi = dict.len() as i32 - 1;
        while lo <= hi {
            let mid = (lo + hi) >> 1;
            match cmp_utf16(&dict[mid as usize], key) {
                Ordering::Less => lo = mid + 1,
                Ordering::Greater => hi = mid - 1,
                Ordering::Equal => return mid,
            }
        }
        -1
    }

    /// Nearest record to `(lat, lon)`, or `None` if nothing is within [`MAX_RADIUS`] cells.
    ///
    /// Expands ring by ring from the query's cell. v2 stopped at the first radius that found
    /// anything, which does **not** give the nearest record: a point just over a cell boundary
    /// is found on ring 1, while something genuinely closer sits on ring 2. This keeps going
    /// until the next ring cannot possibly hold anything nearer than the best already seen.
    fn reverse(&mut self, lat: f64, lon: f64) -> Option<i32> {
        if self.n == 0 {
            return None;
        }
        let q_lat = to_e7(lat);
        let q_lon = to_e7(lon);
        let row = (q_lat as i64 - MIN_LAT_E7 as i64) / CELL_E7;
        let col = (q_lon as i64 - MIN_LON_E7 as i64) / CELL_E7;
        let lon_scale = lat.to_radians().cos();

        let mut best_rec = -1i32;
        let mut best_dist = f64::MAX;
        let mut radius = 1i64;
        while radius <= MAX_RADIUS {
            let mut r = row - radius;
            while r <= row + radius {
                if r >= 0 {
                    let mut c = col - radius;
                    while c <= col + radius {
                        let on_edge = !(radius > 1
                            && r > row - radius
                            && r < row + radius
                            && c > col - radius
                            && c < col + radius);
                        if on_edge {
                            let cc = ((c % COLS) + COLS) % COLS;
                            let cell = r * COLS + cc;
                            let gi = self.grid_index(cell);
                            if gi >= 0 {
                                let start = self.cell_starts[gi as usize];
                                let end = if (gi as usize) + 1 < self.cell_starts.len() {
                                    self.cell_starts[gi as usize + 1]
                                } else {
                                    self.n
                                };
                                for i in start..end {
                                    let d_lat = (self.cols[C_LAT].get(&self.src, i) - q_lat) as f64;
                                    let d_lon = (self.cols[C_LON].get(&self.src, i) - q_lon) as f64
                                        * lon_scale;
                                    let d = d_lat * d_lat + d_lon * d_lon;
                                    if d < best_dist {
                                        best_dist = d;
                                        best_rec = i;
                                    }
                                }
                            }
                        }
                        c += 1;
                    }
                }
                r += 1;
            }
            // Anything on the next ring is at least `radius` whole cells away along one axis,
            // because the query sits somewhere inside ring 0's cell. Once that floor exceeds
            // the best distance found, no further ring can improve on it.
            if best_rec >= 0 {
                let floor = radius as f64 * CELL_E7 as f64 * lon_scale.abs().min(1.0);
                if floor * floor > best_dist {
                    break;
                }
            }
            radius += 1;
        }
        if best_rec < 0 {
            None
        } else {
            Some(best_rec)
        }
    }

    fn compare_key(&mut self, rec: i32, target: [i32; 4]) -> i32 {
        let c = self.cols[C_COUNTRY].get(&self.src, rec) - target[0];
        if c != 0 {
            return c;
        }
        let c = self.cols[C_STATE].get(&self.src, rec) - target[1];
        if c != 0 {
            return c;
        }
        let c = self.cols[C_CITY].get(&self.src, rec) - target[2];
        if c != 0 {
            return c;
        }
        self.cols[C_STREET].get(&self.src, rec) - target[3]
    }

    fn forward(&mut self, country: &str, state: &str, city: &str, street: &str, limit: i32) -> Vec<i32> {
        let k_country = self.dict_index(dict_of(C_COUNTRY), country);
        let k_state = self.dict_index(dict_of(C_STATE), state);
        let k_city = self.dict_index(dict_of(C_CITY), city);
        let k_street = self.dict_index(dict_of(C_STREET), street);
        if k_country < 0 || k_state < 0 || k_city < 0 || k_street < 0 {
            return Vec::new();
        }
        let target = [k_country, k_state, k_city, k_street];
        let mut lo = 0i32;
        let mut hi = self.n;
        while lo < hi {
            let mid = (lo + hi) >> 1;
            let rec = self.fwd.get(&self.src, mid);
            if self.compare_key(rec, target) < 0 {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        let mut out = Vec::new();
        let mut k = lo;
        while k < self.n && (out.len() as i32) < limit {
            let rec = self.fwd.get(&self.src, k);
            if self.compare_key(rec, target) != 0 {
                break;
            }
            out.push(rec);
            k += 1;
        }
        out
    }

    /// Dictionary id range `[start, end)` of names beginning with `prefix`.
    ///
    /// The name dictionary is UTF-16 sorted, so every name with a given prefix forms one
    /// contiguous run and both ends are a binary search. Matching is case-sensitive: the
    /// dictionary is ordered by the names as they appear in OpenStreetMap, and a case-folded
    /// run would not be contiguous in that ordering.
    fn name_prefix_range(&self, prefix: &str) -> (i32, i32) {
        let dict = &self.dicts[dict_of(C_NAME)];
        let len = dict.len() as i32;
        let mut lo = 0i32;
        let mut hi = len;
        while lo < hi {
            let mid = (lo + hi) >> 1;
            if cmp_utf16(&dict[mid as usize], prefix) == Ordering::Less {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        let start = lo;
        let mut lo2 = start;
        let mut hi2 = len;
        while lo2 < hi2 {
            let mid = (lo2 + hi2) >> 1;
            if dict[mid as usize].starts_with(prefix) {
                lo2 = mid + 1;
            } else {
                hi2 = mid;
            }
        }
        (start, lo2)
    }

    /// Records whose name begins with `prefix`, up to `limit`.
    fn search_name(&mut self, prefix: &str, limit: i32) -> Vec<i32> {
        if prefix.is_empty() {
            return Vec::new();
        }
        let (id_lo, id_hi) = self.name_prefix_range(prefix);
        if id_lo >= id_hi {
            return Vec::new();
        }
        let total = self.nm_name.n;
        let mut lo = 0i32;
        let mut hi = total;
        while lo < hi {
            let mid = (lo + hi) >> 1;
            if self.nm_name.get(&self.src, mid) < id_lo {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        let mut out = Vec::new();
        let mut k = lo;
        while k < total && (out.len() as i32) < limit {
            if self.nm_name.get(&self.src, k) >= id_hi {
                break;
            }
            out.push(self.nm_rec.get(&self.src, k));
            k += 1;
        }
        out
    }

    /// `[lat, lon, name, house, street, city, state, country, postcode, kind]` for a
    /// grid-ordered record.
    fn resolve(&mut self, rec: i32) -> [String; FIELDS] {
        let lat = self.cols[C_LAT].get(&self.src, rec) as f64 / 1e7;
        let lon = self.cols[C_LON].get(&self.src, rec) as f64 / 1e7;
        let kind = self.cols[C_KIND].get(&self.src, rec);
        let mut text = |col: usize| -> String {
            let id = self.cols[col].get(&self.src, rec);
            self.dicts[dict_of(col)].get(id as usize).cloned().unwrap_or_default()
        };
        [
            format!("{lat:.7}"),
            format!("{lon:.7}"),
            text(C_NAME),
            text(C_HOUSE),
            text(C_STREET),
            text(C_CITY),
            text(C_STATE),
            text(C_COUNTRY),
            text(C_POSTCODE),
            kind.to_string(),
        ]
    }
}

/// Degrees to e7, the precision OSM itself stores and this database keeps.
fn to_e7(deg: f64) -> i32 {
    (deg * 10_000_000.0 + 0.5).floor() as i32
}

// --------------------------------------------------------------------------- JNI
type Handle = Mutex<Reader>;

fn build_string_array(env: &mut JNIEnv, reader: &mut Reader, recs: &[i32]) -> jobjectArray {
    let string_class = match env.find_class("java/lang/String") {
        Ok(c) => c,
        Err(_) => return std::ptr::null_mut(),
    };
    let empty = match env.new_string("") {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };
    let arr = match env.new_object_array((recs.len() * FIELDS) as i32, &string_class, &empty) {
        Ok(a) => a,
        Err(_) => return std::ptr::null_mut(),
    };
    for (idx, &rec) in recs.iter().enumerate() {
        let fields = reader.resolve(rec);
        for (j, val) in fields.iter().enumerate() {
            let s = match env.new_string(val) {
                Ok(s) => s,
                Err(_) => return std::ptr::null_mut(),
            };
            if env
                .set_object_array_element(&arr, (idx * FIELDS + j) as i32, &s)
                .is_err()
            {
                return std::ptr::null_mut();
            }
        }
    }
    arr.into_raw()
}

/// `open(fd, offset, length) -> handle` (0 on failure). `length` is currently unused (the
/// section framing bounds every read) but kept for API symmetry / future validation.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_GeocoderNative_open<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
    offset: jlong,
    _length: jlong,
) -> jlong {
    match Reader::open(fd, offset) {
        Some(r) => Box::into_raw(Box::new(Mutex::new(r))) as jlong,
        None => 0,
    }
}

/// `reverse(handle, lat, lon) -> String[10]` (nearest record) or null.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_GeocoderNative_reverse<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    lat: jdouble,
    lon: jdouble,
) -> jobjectArray {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    let m = unsafe { &*(handle as *const Handle) };
    let mut reader = match m.lock() {
        Ok(g) => g,
        Err(_) => return std::ptr::null_mut(),
    };
    match reader.reverse(lat, lon) {
        Some(rec) => build_string_array(&mut env, &mut reader, &[rec]),
        None => std::ptr::null_mut(),
    }
}

/// `forward(handle, country, state, city, street, limit) -> String[10*k]` (may be empty).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_GeocoderNative_forward<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    country: JString<'l>,
    state: JString<'l>,
    city: JString<'l>,
    street: JString<'l>,
    limit: jint,
) -> jobjectArray {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    let m = unsafe { &*(handle as *const Handle) };
    let mut reader = match m.lock() {
        Ok(g) => g,
        Err(_) => return std::ptr::null_mut(),
    };
    let get = |env: &mut JNIEnv<'l>, s: &JString<'l>| -> String {
        env.get_string(s).map(|js| js.into()).unwrap_or_default()
    };
    let country = get(&mut env, &country);
    let state = get(&mut env, &state);
    let city = get(&mut env, &city);
    let street = get(&mut env, &street);
    let limit = limit.clamp(1, 50);
    let recs = reader.forward(&country, &state, &city, &street, limit);
    build_string_array(&mut env, &mut reader, &recs)
}

/// `searchName(handle, prefix, limit) -> String[10*k]` (may be empty).
///
/// Finds named features — points of interest, places and streets — by name prefix. v2 had no
/// name column at all, so the only way in was a fully structured address.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_GeocoderNative_searchName<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    prefix: JString<'l>,
    limit: jint,
) -> jobjectArray {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    let m = unsafe { &*(handle as *const Handle) };
    let mut reader = match m.lock() {
        Ok(g) => g,
        Err(_) => return std::ptr::null_mut(),
    };
    let prefix: String = match env.get_string(&prefix) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let recs = reader.search_name(prefix.trim(), limit.clamp(1, 50));
    build_string_array(&mut env, &mut reader, &recs)
}
