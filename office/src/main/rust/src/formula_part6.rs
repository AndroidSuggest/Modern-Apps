impl<'a> Evaluator<'a> {
    fn apply_function2(&mut self, name: &str, a: &[ArgThunk]) -> R<Value> {
        match name {
            // ---- date / time ----
            "DATE" => Ok(Value::Num(date_serial(
                self.t_num(&a[0])? as i64,
                self.t_num(&a[1])? as i64,
                self.t_num(&a[2])? as i64,
            ))),
            "TODAY" => Ok(Value::Num(self.now_serial(false))),
            "NOW" => Ok(Value::Num(self.now_serial(true))),
            "YEAR" => Ok(Value::Num(serial_to_datetime(self.t_num(&a[0])?).year as f64)),
            "MONTH" => Ok(Value::Num(serial_to_datetime(self.t_num(&a[0])?).month as f64)),
            "DAY" => Ok(Value::Num(serial_to_datetime(self.t_num(&a[0])?).day as f64)),
            "HOUR" => Ok(Value::Num(serial_to_datetime(self.t_num(&a[0])?).hour as f64)),
            "MINUTE" => Ok(Value::Num(serial_to_datetime(self.t_num(&a[0])?).minute as f64)),
            "SECOND" => Ok(Value::Num(serial_to_datetime(self.t_num(&a[0])?).second as f64)),
            "WEEKDAY" => Ok(Value::Num(serial_to_datetime(self.t_num(&a[0])?).dow as f64)),
            "TIME" => Ok(Value::Num(
                (self.t_num(&a[0])? * 3600.0 + self.t_num(&a[1])? * 60.0 + self.t_num(&a[2])?) / 86400.0,
            )),

            // ---- multi-criteria aggregation ----
            "SUMIFS" => {
                let sum_vals = self.t_values(&a[0])?;
                let mut pairs = Vec::new();
                let mut i = 1;
                while i + 1 < a.len() {
                    pairs.push((self.t_values(&a[i])?, self.t_value(&a[i + 1])?));
                    i += 2;
                }
                let mut total = 0.0;
                for k in 0..sum_vals.len() {
                    let mut ok = true;
                    for (rng, crit) in &pairs {
                        if !(k < rng.len() && self.matches_criteria(&rng[k], crit)?) {
                            ok = false;
                            break;
                        }
                    }
                    if ok {
                        if let Some(x) = self.num_or_null(&sum_vals[k]) {
                            total += x;
                        }
                    }
                }
                Ok(Value::Num(total))
            }
            "COUNTIFS" => {
                let mut pairs = Vec::new();
                let mut i = 0;
                while i + 1 < a.len() {
                    pairs.push((self.t_values(&a[i])?, self.t_value(&a[i + 1])?));
                    i += 2;
                }
                let len = pairs.first().map(|p| p.0.len()).unwrap_or(0);
                let mut c = 0;
                for k in 0..len {
                    let mut ok = true;
                    for (rng, crit) in &pairs {
                        if !(k < rng.len() && self.matches_criteria(&rng[k], crit)?) {
                            ok = false;
                            break;
                        }
                    }
                    if ok {
                        c += 1;
                    }
                }
                Ok(Value::Num(c as f64))
            }
            "AVERAGEIFS" => {
                let sum_vals = self.t_values(&a[0])?;
                let mut pairs = Vec::new();
                let mut i = 1;
                while i + 1 < a.len() {
                    pairs.push((self.t_values(&a[i])?, self.t_value(&a[i + 1])?));
                    i += 2;
                }
                let mut total = 0.0;
                let mut cnt = 0;
                for k in 0..sum_vals.len() {
                    let mut ok = true;
                    for (rng, crit) in &pairs {
                        if !(k < rng.len() && self.matches_criteria(&rng[k], crit)?) {
                            ok = false;
                            break;
                        }
                    }
                    if ok {
                        if let Some(x) = self.num_or_null(&sum_vals[k]) {
                            total += x;
                            cnt += 1;
                        }
                    }
                }
                if cnt == 0 {
                    Err(ferr("#DIV/0!"))
                } else {
                    Ok(Value::Num(total / cnt as f64))
                }
            }
            "SUMPRODUCT" => {
                let mut arrays = Vec::new();
                for t in a {
                    let vs = self.t_values(t)?;
                    arrays.push(
                        vs.iter()
                            .map(|v| self.num_or_null(v).unwrap_or(0.0))
                            .collect::<Vec<f64>>(),
                    );
                }
                let len = arrays.iter().map(|x| x.len()).min().unwrap_or(0);
                let mut total = 0.0;
                for k in 0..len {
                    let mut p = 1.0;
                    for arr in &arrays {
                        p *= arr[k];
                    }
                    total += p;
                }
                Ok(Value::Num(total))
            }

            // ---- extended math ----
            "SUMSQ" => Ok(Value::Num(self.all_nums(a)?.iter().map(|x| x * x).sum())),
            "MROUND" => {
                let m = self.t_num(&a[1])?;
                if m == 0.0 {
                    Ok(Value::Num(0.0))
                } else {
                    Ok(Value::Num(math_round(self.t_num(&a[0])? / m) as f64 * m))
                }
            }
            "EVEN" => {
                let x = self.t_num(&a[0])?;
                let r = (x.abs() / 2.0).ceil() * 2.0;
                Ok(Value::Num(if x < 0.0 { -r } else { r }))
            }
            "ODD" => {
                let x = self.t_num(&a[0])?;
                let mut r = x.abs().ceil();
                if r % 2.0 == 0.0 {
                    r += 1.0;
                }
                if r < 1.0 {
                    r = 1.0;
                }
                Ok(Value::Num(if x < 0.0 { -r } else { r }))
            }
            "GCD" => {
                let ints: Vec<i64> = self.all_nums(a)?.iter().map(|x| x.abs() as i64).collect();
                let r = ints.into_iter().reduce(gcd_l).unwrap_or(0);
                Ok(Value::Num(r as f64))
            }
            "LCM" => {
                let ints: Vec<i64> = self.all_nums(a)?.iter().map(|x| x.abs() as i64).collect();
                let r = ints
                    .into_iter()
                    .reduce(|x, y| if x == 0 || y == 0 { 0 } else { x / gcd_l(x, y) * y })
                    .unwrap_or(0);
                Ok(Value::Num(r as f64))
            }
            "FACT" => {
                let k = self.t_num(&a[0])? as i64;
                if k < 0 {
                    return Err(ferr("#NUM!"));
                }
                Ok(Value::Num(fact_d(k)))
            }
            "COMBIN" => {
                let nn = self.t_num(&a[0])? as i64;
                let k = self.t_num(&a[1])? as i64;
                if k < 0 || k > nn {
                    return Err(ferr("#NUM!"));
                }
                let mut r = 1.0;
                for i in 0..k {
                    r = r * (nn - i) as f64 / (i + 1) as f64;
                }
                Ok(Value::Num(math_round(r) as f64))
            }
            "RAND" => Ok(Value::Num(self.next_rand())),
            "RANDBETWEEN" => {
                let lo = self.t_num(&a[0])? as i64;
                let hi = self.t_num(&a[1])? as i64;
                Ok(Value::Num((lo + (self.next_rand() * (hi - lo + 1) as f64) as i64) as f64))
            }
            "SIN" => Ok(Value::Num(self.t_num(&a[0])?.sin())),
            "COS" => Ok(Value::Num(self.t_num(&a[0])?.cos())),
            "TAN" => Ok(Value::Num(self.t_num(&a[0])?.tan())),
            "ASIN" => Ok(Value::Num(self.t_num(&a[0])?.asin())),
            "ACOS" => Ok(Value::Num(self.t_num(&a[0])?.acos())),
            "ATAN" => Ok(Value::Num(self.t_num(&a[0])?.atan())),
            "ATAN2" => Ok(Value::Num(self.t_num(&a[1])?.atan2(self.t_num(&a[0])?))),
            "RADIANS" => Ok(Value::Num(self.t_num(&a[0])?.to_radians())),
            "DEGREES" => Ok(Value::Num(self.t_num(&a[0])?.to_degrees())),

            // ---- extended statistics ----
            "STDEVP" => {
                let ns = self.all_nums(a)?;
                if ns.is_empty() {
                    Err(ferr("#DIV/0!"))
                } else {
                    let m = avg_of(&ns);
                    Ok(Value::Num(
                        (ns.iter().map(|x| (x - m).powi(2)).sum::<f64>() / ns.len() as f64).sqrt(),
                    ))
                }
            }
            "VARP" => {
                let ns = self.all_nums(a)?;
                if ns.is_empty() {
                    Err(ferr("#DIV/0!"))
                } else {
                    let m = avg_of(&ns);
                    Ok(Value::Num(ns.iter().map(|x| (x - m).powi(2)).sum::<f64>() / ns.len() as f64))
                }
            }
            "MODE" => {
                let ns = self.all_nums(a)?;
                if ns.is_empty() {
                    return Err(ferr("#N/A"));
                }
                let mut counts: Vec<(f64, usize)> = Vec::new();
                for x in &ns {
                    if let Some(e) = counts.iter_mut().find(|(k, _)| *k == *x) {
                        e.1 += 1;
                    } else {
                        counts.push((*x, 1));
                    }
                }
                let best = counts.iter().max_by_key(|(_, c)| *c).unwrap().0;
                Ok(Value::Num(best))
            }
            "RANK" => {
                let x = self.t_num(&a[0])?;
                let list = self.t_nums(&a[1])?;
                let asc = a.len() > 2 && self.t_truthy(&a[2])?;
                let mut sorted = list.clone();
                if asc {
                    sorted.sort_by(|p, q| p.total_cmp(q));
                } else {
                    sorted.sort_by(|p, q| q.total_cmp(p));
                }
                match sorted.iter().position(|v| *v == x) {
                    Some(idx) => Ok(Value::Num((idx + 1) as f64)),
                    None => Err(ferr("#N/A")),
                }
            }
            "LARGE" => {
                let mut list = self.t_nums(&a[0])?;
                list.sort_by(|p, q| q.total_cmp(p));
                let k = self.t_num(&a[1])? as i64;
                if k < 1 || k as usize > list.len() {
                    Err(ferr("#NUM!"))
                } else {
                    Ok(Value::Num(list[(k - 1) as usize]))
                }
            }
            "SMALL" => {
                let mut list = self.t_nums(&a[0])?;
                list.sort_by(|p, q| p.total_cmp(q));
                let k = self.t_num(&a[1])? as i64;
                if k < 1 || k as usize > list.len() {
                    Err(ferr("#NUM!"))
                } else {
                    Ok(Value::Num(list[(k - 1) as usize]))
                }
            }
            "PERCENTILE" => {
                let mut list = self.t_nums(&a[0])?;
                list.sort_by(|p, q| p.total_cmp(q));
                let p = self.t_num(&a[1])?;
                if list.is_empty() || !(0.0..=1.0).contains(&p) {
                    return Err(ferr("#NUM!"));
                }
                Ok(Value::Num(percentile_of(&list, p)))
            }

            // ---- extended logical ----
            "SWITCH" => {
                let subject = self.t_value(&a[0])?;
                let mut i = 1;
                while i + 1 < a.len() {
                    let cand = self.t_value(&a[i])?;
                    if self.compare_op("=", &subject, &cand)? {
                        return self.t_value(&a[i + 1]);
                    }
                    i += 2;
                }
                if i < a.len() {
                    self.t_value(&a[i])
                } else {
                    Err(ferr("#N/A"))
                }
            }
            "XOR" => {
                let vals = self.all_vals(a)?;
                let f: Vec<bool> = vals.iter().filter_map(bool_or_null).collect();
                Ok(Value::Bool(f.iter().filter(|b| **b).count() % 2 == 1))
            }

            // ---- extended text ----
            "TEXTJOIN" => {
                let delim = self.t_str(&a[0])?;
                let ignore_empty = self.t_truthy(&a[1])?;
                let mut parts = Vec::new();
                for t in &a[2..] {
                    for v in self.t_values(t)? {
                        let s = self.str_of(v)?;
                        if !ignore_empty || !s.is_empty() {
                            parts.push(s);
                        }
                    }
                }
                Ok(Value::Str(parts.join(&delim)))
            }
            "CHAR" => {
                let code = (self.t_num(&a[0])? as i64 as u32) & 0xFFFF;
                Ok(Value::Str(
                    char::from_u32(code).map(|c| c.to_string()).unwrap_or_default(),
                ))
            }
            "CODE" => {
                let s = self.t_str(&a[0])?;
                match s.chars().next() {
                    Some(c) => Ok(Value::Num(c as u32 as f64)),
                    None => Err(ferr("#VALUE!")),
                }
            }
            "T" => {
                let r = self.t_value(&a[0])?;
                if let Value::Str(_) = r {
                    Ok(r)
                } else {
                    Ok(Value::Str(String::new()))
                }
            }
            "CLEAN" => Ok(Value::Str(
                self.t_str(&a[0])?.chars().filter(|c| (*c as u32) >= 0x20).collect(),
            )),
            "NUMBERVALUE" => {
                let s = self.t_str(&a[0])?;
                parse_f64(&s.replace(',', ""))
                    .map(Value::Num)
                    .ok_or_else(|| ferr("#VALUE!"))
            }

            // ---- lookup dimensions ----
            "ROWS" => match self.t_arg(&a[0])? {
                Arg::RangeRef { r1, r2, .. } => Ok(Value::Num(((r2 - r1).abs() + 1) as f64)),
                _ => Ok(Value::Num(1.0)),
            },
            "COLUMNS" => match self.t_arg(&a[0])? {
                Arg::RangeRef { c1, c2, .. } => Ok(Value::Num(((c2 - c1).abs() + 1) as f64)),
                _ => Ok(Value::Num(1.0)),
            },

            _ => self.apply_function3(name, a),
        }
    }
}
