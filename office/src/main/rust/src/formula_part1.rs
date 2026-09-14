fn format_date_time(serial: f64, tokens: &[NumberToken]) -> String {
    let dt = serial_to_datetime(serial);
    let ampm = tokens.iter().any(|t| t.kind == "am-pm");
    let mut sb = String::new();
    for t in tokens {
        match t.kind.as_str() {
            "year" => {
                if t.style.as_deref() == Some("long") {
                    sb.push_str(&dt.year.to_string());
                } else {
                    sb.push_str(&format!("{:02}", dt.year.rem_euclid(100)));
                }
            }
            "month" => {
                let m = (dt.month - 1) as usize;
                let long = t.style.as_deref() == Some("long");
                if t.textual && long {
                    sb.push_str(MONTHS[m]);
                } else if t.textual {
                    sb.push_str(MONTHS_SHORT[m]);
                } else if long {
                    sb.push_str(&format!("{:02}", dt.month));
                } else {
                    sb.push_str(&dt.month.to_string());
                }
            }
            "day" => {
                if t.style.as_deref() == Some("long") {
                    sb.push_str(&format!("{:02}", dt.day));
                } else {
                    sb.push_str(&dt.day.to_string());
                }
            }
            "day-of-week" => {
                let idx = (dt.dow - 1) as usize; // dow: 1=Sun..7=Sat -> 0..6
                if t.style.as_deref() == Some("long") {
                    sb.push_str(WEEKDAYS[idx]);
                } else {
                    sb.push_str(WEEKDAYS_SHORT[idx]);
                }
            }
            "hours" => {
                let h = if ampm {
                    let h12 = dt.hour % 12;
                    if h12 == 0 {
                        12
                    } else {
                        h12
                    }
                } else {
                    dt.hour
                };
                if t.style.as_deref() == Some("long") {
                    sb.push_str(&format!("{:02}", h));
                } else {
                    sb.push_str(&h.to_string());
                }
            }
            "minutes" => {
                if t.style.as_deref() == Some("long") {
                    sb.push_str(&format!("{:02}", dt.minute));
                } else {
                    sb.push_str(&dt.minute.to_string());
                }
            }
            "seconds" => {
                if t.style.as_deref() == Some("long") {
                    sb.push_str(&format!("{:02}", dt.second));
                } else {
                    sb.push_str(&dt.second.to_string());
                }
            }
            "am-pm" => sb.push_str(if dt.hour < 12 { "AM" } else { "PM" }),
            "text" => sb.push_str(t.text.as_deref().unwrap_or("")),
            _ => {}
        }
    }
    sb
}

fn format_date_iso(serial: f64) -> String {
    let dt = serial_to_datetime(serial);
    format!("{:04}-{:02}-{:02}", dt.year, dt.month, dt.day)
}

fn format_time(serial: f64) -> String {
    let total_seconds = math_round((serial - serial.floor()) * 86400.0);
    let h = (total_seconds / 3600) % 24;
    let m = (total_seconds % 3600) / 60;
    let s = total_seconds % 60;
    format!("{:02}:{:02}:{:02}", h, m, s)
}

fn format_fraction(value: f64, denom_digits: i32) -> String {
    let max_den = 10f64.powi(denom_digits.clamp(1, 5)) as i64 - 1;
    let whole = value.trunc() as i64;
    let frac = (value - whole as f64).abs();
    if frac < 1e-9 {
        return whole.to_string();
    }
    let mut best_n = 0i64;
    let mut best_d = 1i64;
    let mut best_err = f64::MAX;
    for d in 1..=max_den {
        let n = math_round(frac * d as f64);
        let err = (frac - n as f64 / d as f64).abs();
        if err < best_err {
            best_err = err;
            best_n = n;
            best_d = d;
        }
    }
    if best_n == 0 {
        return whole.to_string();
    }
    if whole != 0 {
        format!("{} {}/{}", whole, best_n, best_d)
    } else {
        let sign = if value < 0.0 { "-" } else { "" };
        format!("{}{}/{}", sign, best_n, best_d)
    }
}

// ---- civil date arithmetic (no GregorianCalendar) --------------------------

struct DateTime {
    year: i64,
    month: i64, // 1..12
    day: i64,   // 1..31
    hour: i64,
    minute: i64,
    second: i64,
    dow: i64, // 1=Sunday .. 7=Saturday (Java Calendar convention)
}

/// Howard Hinnant's days_from_civil: days since 1970-01-01 for a proleptic Gregorian date.
fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400; // [0, 399]
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + d - 1; // [0, 365]
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy; // [0, 146096]
    era * 146097 + doe - 719468
}

/// Inverse of days_from_civil: (year, month, day) from days since 1970-01-01.
fn civil_from_days(z: i64) -> (i64, i64, i64) {
    let z = z + 719468;
    let era = if z >= 0 { z } else { z - 146096 } / 146097;
    let doe = z - era * 146097; // [0, 146096]
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365; // [0, 399]
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100); // [0, 365]
    let mp = (5 * doy + 2) / 153; // [0, 11]
    let d = doy - (153 * mp + 2) / 5 + 1; // [1, 31]
    let m = if mp < 10 { mp + 3 } else { mp - 9 }; // [1, 12]
    (if m <= 2 { y + 1 } else { y }, m, d)
}

fn is_leap(y: i64) -> bool {
    (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
}

fn days_in_month(y: i64, m: i64) -> i64 {
    match m {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 => {
            if is_leap(y) {
                29
            } else {
                28
            }
        }
        _ => 30,
    }
}

fn serial_to_datetime(serial: f64) -> DateTime {
    let total_ms = math_round(serial * MS_PER_DAY);
    let day_ms = 86_400_000i64;
    let days = total_ms.div_euclid(day_ms);
    let ms_in_day = total_ms.rem_euclid(day_ms);
    let (year, month, day) = civil_from_days(days - EPOCH_OFFSET_DAYS);
    let hour = ms_in_day / 3_600_000;
    let minute = (ms_in_day / 60_000) % 60;
    let second = (ms_in_day / 1000) % 60;
    // day-of-week: serial 1 (1899-12-31) is Sunday. Java: Sun=1..Sat=7.
    let r = days.rem_euclid(7);
    let dow = if r == 0 { 7 } else { r };
    DateTime {
        year,
        month,
        day,
        hour,
        minute,
        second,
        dow,
    }
}

/// DATE(y,m,d) serial — lenient like GregorianCalendar(y, m-1, d).
fn date_serial(y: i64, m: i64, d: i64) -> f64 {
    let mm = m - 1;
    let year2 = y + mm.div_euclid(12);
    let month0 = mm.rem_euclid(12); // 0..11
    let base = days_from_civil(year2, month0 + 1, 1) + EPOCH_OFFSET_DAYS;
    (base + (d - 1)) as f64
}

/// GregorianCalendar.add(MONTH, k) semantics: shift month, clamp day to month length.
fn add_months(y: i64, m: i64, d: i64, k: i64) -> (i64, i64, i64) {
    let total = (m - 1) + k;
    let y2 = y + total.div_euclid(12);
    let m2 = total.rem_euclid(12) + 1;
    let d2 = d.min(days_in_month(y2, m2));
    (y2, m2, d2)
}

// ---- evaluator -------------------------------------------------------------

struct Evaluator<'a> {
    wb: &'a Workbook,
    sheet_idx: usize,
    sheet_name: String,
    cur_row: i32,
    cur_col: i32,
    visiting: &'a RefCell<HashSet<String>>,
}
