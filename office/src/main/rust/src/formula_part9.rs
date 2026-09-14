            Arg::RangeRef { r1, c1, r2, c2, .. } => (r1, c1, r2, c2),
            _ => return Err(ferr("#N/A")),
        };
        let index = self.t_num(&a[2])? as i32;
        let approx = if a.len() > 3 {
            self.t_truthy(&a[3])?
        } else {
            true
        };
        let r0 = r1.min(r2);
        let r1e = r1.max(r2);
        let c0 = c1.min(c2);
        let c1e = c1.max(c2);
        let mut found_line = -1i32;
        if horizontal {
            let mut best = -1i32;
            for c in c0..=c1e {
                let cv = self.evaluate_cell_v(r0, c)?;
                if self.compare_op("=", &cv, &key)? {
                    found_line = c;
                    break;
                }
                if approx {
                    if let (Some(a), Some(b)) = (self.cmp_num(&cv)?, self.cmp_num(&key)?) {
                        if a <= b {
                            best = c;
                        }
                    }
                }
            }
            if found_line < 0 {
                found_line = best;
            }
            if found_line < 0 {
                return Err(ferr("#N/A"));
            }
            let target_row = r0 + index - 1;
            if target_row > r1e {
                return Err(ferr("#REF!"));
            }
            self.evaluate_cell_v(target_row, found_line)
        } else {
            let mut best = -1i32;
            for r in r0..=r1e {
                let cv = self.evaluate_cell_v(r, c0)?;
                if self.compare_op("=", &cv, &key)? {
                    found_line = r;
                    break;
                }
                if approx {
                    if let (Some(a), Some(b)) = (self.cmp_num(&cv)?, self.cmp_num(&key)?) {
                        if a <= b {
                            best = r;
                        }
                    }
                }
            }
            if found_line < 0 {
                found_line = best;
            }
            if found_line < 0 {
                return Err(ferr("#N/A"));
            }
            let target_col = c0 + index - 1;
            if target_col > c1e {
                return Err(ferr("#REF!"));
            }
            self.evaluate_cell_v(found_line, target_col)
        }
    }

    fn match_fn(&mut self, a: &[ArgThunk]) -> R<Value> {
        let key = self.t_value(&a[0])?;
        let (r1, c1, r2, c2) = match self.t_arg(&a[1])? {
            Arg::RangeRef { r1, c1, r2, c2, .. } => (r1, c1, r2, c2),
            _ => return Err(ferr("#N/A")),
        };
        let ty = if a.len() > 2 { self.t_num(&a[2])? as i32 } else { 1 };
        let mut cells: Vec<Value> = Vec::new();
        for r in r1.min(r2)..=r1.max(r2) {
            for c in c1.min(c2)..=c1.max(c2) {
                cells.push(self.evaluate_cell_v(r, c)?);
            }
        }
        match ty {
            0 => {
                for (i, cv) in cells.iter().enumerate() {
                    if self.compare_op("=", cv, &key)? {
                        return Ok(Value::Num((i + 1) as f64));
                    }
                }
                Err(ferr("#N/A"))
            }
            1 => {
                let mut best = -1i32;
                let k = self.cmp_num(&key)?;
                for (i, cv) in cells.iter().enumerate() {
                    if let (Some(kk), Some(n)) = (k, self.cmp_num(cv)?) {
                        if n <= kk {
                            best = i as i32;
                        }
                    }
                }
                if best < 0 {
                    Err(ferr("#N/A"))
                } else {
                    Ok(Value::Num((best + 1) as f64))
                }
            }
            _ => {
                let mut best = -1i32;
                let k = self.cmp_num(&key)?;
                for (i, cv) in cells.iter().enumerate() {
                    if let (Some(kk), Some(n)) = (k, self.cmp_num(cv)?) {
                        if n >= kk {
                            best = i as i32;
                        }
                    }
                }
                if best < 0 {
                    Err(ferr("#N/A"))
                } else {
                    Ok(Value::Num((best + 1) as f64))
                }
            }
        }
    }

    fn index_fn(&mut self, a: &[ArgThunk]) -> R<Value> {
        let (r1, c1, r2, c2) = match self.t_arg(&a[0])? {
            Arg::RangeRef { r1, c1, r2, c2, .. } => (r1, c1, r2, c2),
            _ => return self.t_value(&a[0]),
        };
        let r0 = r1.min(r2);
        let c0 = c1.min(c2);
        let rn = if a.len() > 1 { self.t_num(&a[1])? as i32 } else { 1 };
        let cn = if a.len() > 2 { self.t_num(&a[2])? as i32 } else { 1 };
        let target_row = if rn <= 0 { r0 } else { r0 + rn - 1 };
        let target_col = if cn <= 0 { c0 } else { c0 + cn - 1 };
        if target_row > r1.max(r2) || target_col > c1.max(c2) {
            return Err(ferr("#REF!"));
        }
        self.evaluate_cell_v(target_row, target_col)
    }
}

// ---- free numeric/text helpers ---------------------------------------------

fn correl(xs: &[f64], ys: &[f64]) -> R<f64> {
    let n = xs.len();
    if n == 0 {
        return Err(ferr("#DIV/0!"));
    }
    let mx = avg_of(xs);
    let my = avg_of(ys);
    let (mut sxy, mut sxx, mut syy) = (0.0, 0.0, 0.0);
    for i in 0..n {
        sxy += (xs[i] - mx) * (ys[i] - my);
        sxx += (xs[i] - mx).powi(2);
        syy += (ys[i] - my).powi(2);
    }
    let d = (sxx * syy).sqrt();
    if d == 0.0 {
        Err(ferr("#DIV/0!"))
    } else {
        Ok(sxy / d)
    }
}

fn slope(ys: &[f64], xs: &[f64]) -> R<f64> {
    let n = xs.len();
    if n == 0 {
        return Err(ferr("#DIV/0!"));
    }
    let mx = avg_of(xs);
    let my = avg_of(ys);
    let (mut num, mut den) = (0.0, 0.0);
    for i in 0..n {
        num += (xs[i] - mx) * (ys[i] - my);
        den += (xs[i] - mx).powi(2);
    }
    if den == 0.0 {
        Err(ferr("#DIV/0!"))
    } else {
        Ok(num / den)
    }
}

fn percentile_of(sorted: &[f64], p: f64) -> f64 {
    let rank = p * (sorted.len() - 1) as f64;
    let lo = rank.floor() as usize;
    let hi = rank.ceil() as usize;
    if lo == hi {
        sorted[lo]
    } else {
        sorted[lo] + (rank - lo as f64) * (sorted[hi] - sorted[lo])
    }
}

fn text_before_after(text: &str, delim: &str, instance: i32, before: bool) -> R<Value> {
    if delim.is_empty() {
        return Ok(Value::Str(if before {
            String::new()
        } else {
            text.to_string()
        }));
    }
    let tc: Vec<char> = text.chars().collect();
    let dc: Vec<char> = delim.chars().collect();
    let mut idx: i32 = -1;
    let mut count = 0;
    let mut from = 0usize;
    loop {
        let f = find_chars(&tc, &dc, from);
        match f {
            Some(pos) => {
                count += 1;
                if count == instance {
                    idx = pos as i32;
                    break;
                }
                from = pos + dc.len();
            }
            None => break,
        }
    }
    if idx < 0 {
        return Err(ferr("#N/A"));
    }
    let idx = idx as usize;
    if before {
        Ok(Value::Str(tc[..idx].iter().collect()))
    } else {
        Ok(Value::Str(tc[idx + dc.len()..].iter().collect()))
    }
}

fn find_chars(hay: &[char], needle: &[char], from: usize) -> Option<usize> {
    if needle.is_empty() {
        return Some(from.min(hay.len()));
    }
    let mut i = from;
    while i + needle.len() <= hay.len() {
        if hay[i..i + needle.len()] == needle[..] {
            return Some(i);
        }
        i += 1;
    }
    None
}

fn days360(start: f64, end: f64, european: bool) -> i64 {
    let c1 = serial_to_datetime(start);
    let c2 = serial_to_datetime(end);
    let mut d1 = c1.day;
    let mut d2 = c2.day;
    let m1 = c1.month;
    let m2 = c2.month;
    let y1 = c1.year;
    let y2 = c2.year;
    if european {
        if d1 == 31 {
            d1 = 30;
        }
        if d2 == 31 {
            d2 = 30;
        }
    } else {
        if d1 == 31 {
            d1 = 30;
        }
        if d2 == 31 && d1 == 30 {
            d2 = 30;
        }
    }
    (y2 - y1) * 360 + (m2 - m1) * 30 + (d2 - d1)
}

fn year_frac(start: f64, end: f64, basis: i32) -> f64 {
    let days = (end.floor() - start.floor()).abs();
    match basis {
        0 => (days360(start, end, false).abs() as f64) / 360.0,
        1 => days / 365.25,
        2 => days / 360.0,
        3 => days / 365.0,
        4 => (days360(start, end, true).abs() as f64) / 360.0,
        _ => days / 365.0,
    }
}

fn weeknum(serial: f64) -> i64 {
    let dt = serial_to_datetime(serial);
    let jan1 = date_serial(dt.year, 1, 1);
    let jan1_dow = serial_to_datetime(jan1).dow; // 1=Sun..7=Sat
    let doy = serial.floor() as i64 - jan1 as i64 + 1; // 1-based day of year
    (doy - 1 + (jan1_dow - 1)) / 7 + 1
}

fn iso_week_num(serial: f64) -> i64 {
    // ISO 8601: week 1 is the week (Mon-Sun) containing the first Thursday.
    let days = serial.floor() as i64;
    // Convert to a Monday-based ordinal (0=Mon..6=Sun).
    let dt = serial_to_datetime(serial);
    let iso_dow = ((dt.dow + 5) % 7) + 1; // 1=Mon..7=Sun
    // Thursday of this week
    let thursday = days - (iso_dow - 4);
    let tdt = serial_to_datetime(thursday as f64);
    let jan1 = date_serial(tdt.year, 1, 1) as i64;
    (thursday - jan1) / 7 + 1
}

fn fv_of(r: f64, nper: f64, pmt: f64, pv: f64, ty: i32) -> f64 {
    if r == 0.0 {
        -(pv + pmt * nper)
    } else {
        let p = (1.0 + r).powf(nper);
        -(pv * p + pmt * (1.0 + r * ty as f64) * (p - 1.0) / r)
    }
}

fn pmt_calc(r: f64, nper: f64, pv: f64, fv: f64, ty: i32) -> f64 {
    if r == 0.0 {
        -(pv + fv) / nper
    } else {
        let p = (1.0 + r).powf(nper);
        -(pv * p + fv) * r / ((1.0 + r * ty as f64) * (p - 1.0))
    }
}

fn ipmt_calc(r: f64, per: i32, nper: f64, pv: f64, fv: f64, ty: i32) -> f64 {
    let pmt = pmt_calc(r, nper, pv, fv, ty);
    let mut ip = fv_of(r, (per - 1) as f64, pmt, pv, ty) * r;
    if ty == 1 {
        ip /= 1.0 + r;
    }
    ip
}

fn a1_to_coords(t: &str) -> Option<(i32, i32)> {
    let cell = substring_after_last(t, '.').replace('$', "");
    let cell = cell.trim();
    let chars: Vec<char> = cell.chars().collect();
    let mut i = 0;
    while i < chars.len() && !chars[i].is_ascii_alphabetic() {
        i += 1;
    }
    let ls = i;
    while i < chars.len() && chars[i].is_ascii_alphabetic() {
        i += 1;
    }
    let letters: String = chars[ls..i].iter().collect();
    let ds = i;
    while i < chars.len() && chars[i].is_ascii_digit() {
        i += 1;
    }
    let digits: String = chars[ds..i].iter().collect();
    if letters.is_empty() || digits.is_empty() {
        return None;
    }
    let row = digits.parse::<i32>().ok()? - 1;
    Some((row, col_to_index(&letters)))
}

fn text_format(value: f64, fmt: &str) -> String {
    let f = fmt.trim();
    if f.contains('%') {
        let decimals = substring_after(f, '.').chars().filter(|c| *c == '0' || *c == '#').count();
        return format!("{}%", format_fixed(value * 100.0, decimals, false));
    }
    let grouping = f.contains(',');
    let decimals = substring_after(f, '.').chars().filter(|c| *c == '0' || *c == '#').count();
    format_fixed(value, decimals, grouping)
}

fn substring_after(s: &str, delim: char) -> String {
    match s.find(delim) {
        Some(i) => s[i + delim.len_utf8()..].to_string(),
        None => String::new(),
    }
}
