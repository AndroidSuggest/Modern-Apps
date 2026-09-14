impl<'a> Evaluator<'a> {
    fn apply_function4(&mut self, name: &str, a: &[ArgThunk]) -> R<Value> {
        match name {
            // ---- statistics (phase 1) ----
            "GEOMEAN" => {
                let ns = self.all_nums(a)?;
                if ns.is_empty() {
                    Err(ferr("#NUM!"))
                } else {
                    Ok(Value::Num((ns.iter().map(|x| x.ln()).sum::<f64>() / ns.len() as f64).exp()))
                }
            }
            "HARMEAN" => {
                let ns = self.all_nums(a)?;
                if ns.is_empty() {
                    Err(ferr("#NUM!"))
                } else {
                    Ok(Value::Num(ns.len() as f64 / ns.iter().map(|x| 1.0 / x).sum::<f64>()))
                }
            }
            "AVEDEV" => {
                let ns = self.all_nums(a)?;
                if ns.is_empty() {
                    Err(ferr("#NUM!"))
                } else {
                    let m = avg_of(&ns);
                    Ok(Value::Num(ns.iter().map(|x| (x - m).abs()).sum::<f64>() / ns.len() as f64))
                }
            }
            "DEVSQ" => {
                let ns = self.all_nums(a)?;
                if ns.is_empty() {
                    Ok(Value::Num(0.0))
                } else {
                    let m = avg_of(&ns);
                    Ok(Value::Num(ns.iter().map(|x| (x - m).powi(2)).sum()))
                }
            }
            "CORREL" | "PEARSON" => {
                let (xs, ys) = self.pair(a, 0, 1)?;
                Ok(Value::Num(correl(&xs, &ys)?))
            }
            "COVAR" => {
                let (xs, ys) = self.pair(a, 0, 1)?;
                if xs.is_empty() {
                    Err(ferr("#DIV/0!"))
                } else {
                    let mx = avg_of(&xs);
                    let my = avg_of(&ys);
                    let mut t = 0.0;
                    for i in 0..xs.len() {
                        t += (xs[i] - mx) * (ys[i] - my);
                    }
                    Ok(Value::Num(t / xs.len() as f64))
                }
            }
            "SLOPE" => {
                let (ys, xs) = self.pair(a, 0, 1)?;
                Ok(Value::Num(slope(&ys, &xs)?))
            }
            "INTERCEPT" => {
                let (ys, xs) = self.pair(a, 0, 1)?;
                Ok(Value::Num(avg_of(&ys) - slope(&ys, &xs)? * avg_of(&xs)))
            }
            "FORECAST" => {
                let x = self.t_num(&a[0])?;
                let ys = self.t_nums(&a[1])?;
                let xs = self.t_nums(&a[2])?;
                let m = xs.len().min(ys.len());
                let yy = ys[..m].to_vec();
                let xx = xs[..m].to_vec();
                Ok(Value::Num(avg_of(&yy) + slope(&yy, &xx)? * (x - avg_of(&xx))))
            }
            "QUARTILE" => {
                let mut list = self.t_nums(&a[0])?;
                list.sort_by(|p, q| p.total_cmp(q));
                let q = self.t_num(&a[1])? as i32;
                if list.is_empty() || !(0..=4).contains(&q) {
                    Err(ferr("#NUM!"))
                } else {
                    Ok(Value::Num(percentile_of(&list, q as f64 / 4.0)))
                }
            }
            "PERCENTRANK" => {
                let mut list = self.t_nums(&a[0])?;
                list.sort_by(|p, q| p.total_cmp(q));
                let x = self.t_num(&a[1])?;
                if list.is_empty() {
                    return Err(ferr("#NUM!"));
                }
                let res = if x <= *list.first().unwrap() {
                    0.0
                } else if x >= *list.last().unwrap() {
                    1.0
                } else {
                    let mut i = 0;
                    while i < list.len() && list[i] <= x {
                        i += 1;
                    }
                    let lo = i - 1;
                    let frac = if list[i] == list[lo] {
                        0.0
                    } else {
                        (x - list[lo]) / (list[i] - list[lo])
                    };
                    (lo as f64 + frac) / (list.len() - 1) as f64
                };
                Ok(Value::Num(res))
            }
            "AVERAGEA" => {
                let vs = self.vals_a(a)?;
                if vs.is_empty() {
                    Err(ferr("#DIV/0!"))
                } else {
                    Ok(Value::Num(avg_of(&vs)))
                }
            }
            "MAXA" => Ok(Value::Num(pipe_max(&self.vals_a(a)?))),
            "MINA" => Ok(Value::Num(pipe_min(&self.vals_a(a)?))),

            // ---- text (phase 1) ----
            "FIXED" => {
                let dec = if a.len() > 1 { self.t_num(&a[1])? as i32 } else { 2 }.max(0) as usize;
                let no_comma = a.len() > 2 && self.t_truthy(&a[2])?;
                Ok(Value::Str(format_fixed(self.t_num(&a[0])?, dec, !no_comma)))
            }
            "DOLLAR" => {
                let dec = if a.len() > 1 { self.t_num(&a[1])? as i32 } else { 2 }.max(0) as usize;
                Ok(Value::Str(format!("${}", format_fixed(self.t_num(&a[0])?, dec, true))))
            }
            "UNICHAR" => {
                let cp = self.t_num(&a[0])? as i64;
                if cp <= 0 {
                    Err(ferr("#VALUE!"))
                } else {
                    match char::from_u32(cp as u32) {
                        Some(c) => Ok(Value::Str(c.to_string())),
                        None => Err(ferr("#VALUE!")),
                    }
                }
            }
            "UNICODE" => {
                let s = self.t_str(&a[0])?;
                match s.chars().next() {
                    Some(c) => Ok(Value::Num(c as u32 as f64)),
                    None => Err(ferr("#VALUE!")),
                }
            }
            "TEXTBEFORE" => {
                let text = self.t_str(&a[0])?;
                let delim = self.t_str(&a[1])?;
                let inst = if a.len() > 2 { self.t_num(&a[2])? as i32 } else { 1 };
                text_before_after(&text, &delim, inst, true)
            }
            "TEXTAFTER" => {
                let text = self.t_str(&a[0])?;
                let delim = self.t_str(&a[1])?;
                let inst = if a.len() > 2 { self.t_num(&a[2])? as i32 } else { 1 };
                text_before_after(&text, &delim, inst, false)
            }

            // ---- date (phase 1) ----
            "YEARFRAC" => {
                let s = self.t_num(&a[0])?;
                let e = self.t_num(&a[1])?;
                let basis = if a.len() > 2 { self.t_num(&a[2])? as i32 } else { 0 };
                Ok(Value::Num(year_frac(s, e, basis)))
            }
            "ISOWEEKNUM" => Ok(Value::Num(iso_week_num(self.t_num(&a[0])?) as f64)),
            "DAYS360" => {
                let s = self.t_num(&a[0])?;
                let e = self.t_num(&a[1])?;
                let european = a.len() > 2 && self.t_truthy(&a[2])?;
                Ok(Value::Num(days360(s, e, european) as f64))
            }

            // ---- financial (phase 1) ----
            "RATE" => {
                let nper = self.t_num(&a[0])?;
                let pmt = self.t_num(&a[1])?;
                let pv = self.t_num(&a[2])?;
                let fv = if a.len() > 3 { self.t_num(&a[3])? } else { 0.0 };
                let ty = if a.len() > 4 { self.t_num(&a[4])? as i32 } else { 0 };
                let mut r = if a.len() > 5 { self.t_num(&a[5])? } else { 0.1 };
                let f = |rate: f64| -> f64 {
                    if rate == 0.0 {
                        pv + pmt * nper + fv
                    } else {
                        pv * (1.0 + rate).powf(nper)
                            + pmt * (1.0 + rate * ty as f64) * ((1.0 + rate).powf(nper) - 1.0) / rate
                            + fv
                    }
                };
                for _ in 0..100 {
                    let dr = 1e-6;
                    let d = (f(r + dr) - f(r)) / dr;
                    if d == 0.0 {
                        break;
                    }
                    let nr = r - f(r) / d;
                    if (nr - r).abs() < 1e-9 {
                        r = nr;
                        break;
                    }
                    r = nr;
                }
                Ok(Value::Num(r))
            }
            "IPMT" => {
                let r = self.t_num(&a[0])?;
                let per = self.t_num(&a[1])? as i32;
                let nper = self.t_num(&a[2])?;
                let pv = self.t_num(&a[3])?;
                let fv = if a.len() > 4 { self.t_num(&a[4])? } else { 0.0 };
                let ty = if a.len() > 5 { self.t_num(&a[5])? as i32 } else { 0 };
                Ok(Value::Num(ipmt_calc(r, per, nper, pv, fv, ty)))
            }
            "PPMT" => {
                let r = self.t_num(&a[0])?;
                let per = self.t_num(&a[1])? as i32;
                let nper = self.t_num(&a[2])?;
                let pv = self.t_num(&a[3])?;
                let fv = if a.len() > 4 { self.t_num(&a[4])? } else { 0.0 };
                let ty = if a.len() > 5 { self.t_num(&a[5])? as i32 } else { 0 };
                Ok(Value::Num(pmt_calc(r, nper, pv, fv, ty) - ipmt_calc(r, per, nper, pv, fv, ty)))
            }
            "SLN" => Ok(Value::Num(
                (self.t_num(&a[0])? - self.t_num(&a[1])?) / self.t_num(&a[2])?,
            )),
            "SYD" => {
                let cost = self.t_num(&a[0])?;
                let salvage = self.t_num(&a[1])?;
                let life = self.t_num(&a[2])?;
                let per = self.t_num(&a[3])?;
                Ok(Value::Num((cost - salvage) * (life - per + 1.0) * 2.0 / (life * (life + 1.0))))
            }
            "IRR" => {
                let flows = self.t_nums(&a[0])?;
                let mut r = if a.len() > 1 { self.t_num(&a[1])? } else { 0.1 };
                for _ in 0..100 {
                    let mut npv = 0.0;
                    let mut d = 0.0;
                    for (t, flow) in flows.iter().enumerate() {
                        npv += flow / (1.0 + r).powi(t as i32);
                        if t > 0 {
                            d += -(t as f64) * flow / (1.0 + r).powi(t as i32 + 1);
                        }
                    }
                    if d == 0.0 {
                        break;
                    }
                    let nr = r - npv / d;
                    if (nr - r).abs() < 1e-9 {
                        r = nr;
                        break;
                    }
                    r = nr;
                }
                Ok(Value::Num(r))
            }
            "CUMIPMT" => {
                let r = self.t_num(&a[0])?;
                let nper = self.t_num(&a[1])?;
                let pv = self.t_num(&a[2])?;
                let s = self.t_num(&a[3])? as i32;
                let e = self.t_num(&a[4])? as i32;
                let ty = self.t_num(&a[5])? as i32;
                let mut t = 0.0;
                for p in s..=e {
                    t += ipmt_calc(r, p, nper, pv, 0.0, ty);
                }
                Ok(Value::Num(t))
            }
            "CUMPRINC" => {
                let r = self.t_num(&a[0])?;
                let nper = self.t_num(&a[1])?;
                let pv = self.t_num(&a[2])?;
                let s = self.t_num(&a[3])? as i32;
                let e = self.t_num(&a[4])? as i32;
                let ty = self.t_num(&a[5])? as i32;
                let pmt = pmt_calc(r, nper, pv, 0.0, ty);
                let mut t = 0.0;
                for p in s..=e {
                    t += pmt - ipmt_calc(r, p, nper, pv, 0.0, ty);
                }
                Ok(Value::Num(t))
            }

            // ---- lookup (phase 1) ----
            "ROW" => {
                if a.is_empty() {
                    Ok(Value::Num((self.cur_row + 1) as f64))
                } else {
                    let r = self.t_ref(&a[0]).ok_or_else(|| ferr("#REF!"))?;
                    Ok(Value::Num((r.r1.min(r.r2) + 1) as f64))
                }
            }
            "COLUMN" => {
                if a.is_empty() {
                    Ok(Value::Num((self.cur_col + 1) as f64))
                } else {
                    let r = self.t_ref(&a[0]).ok_or_else(|| ferr("#REF!"))?;
                    Ok(Value::Num((r.c1.min(r.c2) + 1) as f64))
                }
            }
            "LOOKUP" => {
                let key = self.t_value(&a[0])?;
                let lv = self.t_values(&a[1])?;
                let rv = if a.len() > 2 { self.t_values(&a[2])? } else { lv.clone() };
                let mut best: i32 = -1;
                let k = self.cmp_num(&key)?;
                for (i, item) in lv.iter().enumerate() {
                    if self.compare_op("=", item, &key)? {
                        best = i as i32;
                        break;
                    }
                    let nv = self.cmp_num(item)?;
                    if let (Some(kk), Some(n)) = (k, nv) {
                        if n <= kk {
                            best = i as i32;
                        }
                    }
                }
                if best < 0 || best as usize >= rv.len() {
                    Err(ferr("#N/A"))
                } else {
                    Ok(rv[best as usize].clone())
                }
            }
            "OFFSET" => {
                let r = self.t_ref(&a[0]).ok_or_else(|| ferr("#REF!"))?;
                let dr = self.t_num(&a[1])? as i32;
                let dc = self.t_num(&a[2])? as i32;
                self.eval_on(&r.sheet, r.r1.min(r.r2) + dr, r.c1.min(r.c2) + dc)
            }
            "ADDRESS" => {
                let row = self.t_num(&a[0])? as i32;
                let col = self.t_num(&a[1])? as i32;
                let abs_num = if a.len() > 2 { self.t_num(&a[2])? as i32 } else { 1 };
                let col_str = index_to_col(col - 1);
                let res = match abs_num {
                    1 => format!("${}${}", col_str, row),
                    2 => format!("{}${}", col_str, row),
                    3 => format!("${}{}", col_str, row),
                    _ => format!("{}{}", col_str, row),
                };
                if a.len() > 4 {
                    let sheet = self.t_str(&a[4])?;
                    if !sheet.is_empty() {
                        return Ok(Value::Str(format!("{}.{}", sheet, res)));
                    }
                }
                Ok(Value::Str(res))
            }
            "INDIRECT" => {
                let s = self.t_str(&a[0])?;
                let (rr, cc) = a1_to_coords(&s).ok_or_else(|| ferr("#REF!"))?;
                self.evaluate_cell_v(rr, cc)
            }
            "HYPERLINK" => Ok(Value::Str(if a.len() > 1 {
                self.t_str(&a[1])?
            } else {
                self.t_str(&a[0])?
            })),

            _ => Err(ferr("#NAME?")),
        }
    }

    // ---- function helpers --------------------------------------------------

    fn is_error(&mut self, t: &ArgThunk) -> Option<String> {
        match self.t_value(t) {
            Ok(Value::Err(c)) => Some(c),
            Ok(_) => None,
            Err(e) => Some(e.0),
        }
    }

    fn safe_value(&mut self, t: &ArgThunk) -> Value {
        match self.t_value(t) {
            Ok(v) => v,
            Err(e) => Value::Err(e.0),
        }
    }

    fn for_each_range_cell(&mut self, arg: &Arg) -> R<Vec<Value>> {
        let mut out = Vec::new();
        match arg {
            Arg::Scalar(v) => out.push(v.clone()),
            Arg::RangeRef { r1, c1, r2, c2, .. } => {
                for r in (*r1).min(*r2)..=(*r1).max(*r2) {
                    for c in (*c1).min(*c2)..=(*c1).max(*c2) {
                        if let Some(cell) = cell_at(self.sheet(), r, c) {
                            if cell.is_covered {
                                continue;
                            }
                        }
                        out.push(self.evaluate_cell_v(r, c)?);
                    }
                }
            }
        }
        Ok(out)
    }

    fn conditional_agg(
        &mut self,
        a: &[ArgThunk],
        _sum_kind: bool,
        reduce: fn(&[f64]) -> f64,
    ) -> R<Value> {
        let range_arg = self.t_arg(&a[0])?;
        let crit = self.t_value(&a[1])?;
        let sum_arg = if a.len() > 2 {
            self.t_arg(&a[2])?
        } else {
            range_arg.clone()
        };
        let mut matched: Vec<f64> = Vec::new();
        if let Arg::RangeRef { r1, c1, r2, c2, .. } = range_arg {
            let r0 = r1.min(r2);
            let c0 = c1.min(c2);
            let (rs0, cs0) = match &sum_arg {
                Arg::RangeRef { r1, c1, r2, c2, .. } => ((*r1).min(*r2), (*c1).min(*c2)),
                _ => (r0, c0),
            };
            for r in r0..=r1.max(r2) {
                for c in c0..=c1.max(c2) {
                    if let Some(cell) = cell_at(self.sheet(), r, c) {
                        if cell.is_covered {
                            continue;
                        }
                    }
                    let cv = self.evaluate_cell_v(r, c)?;
                    if self.matches_criteria(&cv, &crit)? {
                        let sv = self.evaluate_cell_v(rs0 + (r - r0), cs0 + (c - c0))?;
                        if let Some(x) = self.num_or_null(&sv) {
                            matched.push(x);
                        }
                    }
                }
            }
        }
        Ok(Value::Num(reduce(&matched)))
    }

    fn lookup(&mut self, a: &[ArgThunk], horizontal: bool) -> R<Value> {
        let key = self.t_value(&a[0])?;
        let (r1, c1, r2, c2) = match self.t_arg(&a[1])? {