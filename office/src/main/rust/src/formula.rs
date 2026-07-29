//! OpenFormula spreadsheet evaluator (the native owner of the ODF formula engine).
//!
//! Value-typed engine: numbers, strings, booleans, error values. Recursive-descent
//! parser (comparison -> concat -> add/sub -> mul/div -> power -> unary -> primary),
//! ~150 functions, cross-sheet references with cycle detection, ODF number
//! formatting, and civil-date arithmetic (serial = days since 1899-12-30).
//!
//! Determinism: `TODAY`/`NOW` derive from a caller-supplied `now_millis`, and
//! `RAND`/`RANDBETWEEN` use a PRNG seeded from it (Kotlin's are non-deterministic;
//! we only require reproducibility here).

use serde::Deserialize;
use std::cell::RefCell;
use std::collections::{HashMap, HashSet};

const MS_PER_DAY: f64 = 86_400_000.0;
/// Days from the spreadsheet epoch (1899-12-30) to the Unix epoch (1970-01-01).
const EPOCH_OFFSET_DAYS: i64 = 25569;

// ---- serde workbook schema (must match the Kotlin serializer in OfficeNative) --

#[derive(Deserialize)]
struct WorkbookJson {
    sheets: Vec<SheetJson>,
}

#[derive(Deserialize)]
struct SheetJson {
    #[serde(default)]
    name: String,
    #[serde(default)]
    rows: Vec<RowJson>,
}

#[derive(Deserialize)]
struct RowJson {
    #[serde(default)]
    cells: Vec<Cell>,
}

#[derive(Deserialize, Clone, Default)]
pub struct Cell {
    #[serde(default)]
    text: String,
    #[serde(default)]
    formula: Option<String>,
    #[serde(default, rename = "numberValue")]
    number_value: Option<f64>,
    #[serde(default, rename = "valueType")]
    value_type: Option<String>,
    #[serde(default, rename = "isCovered")]
    is_covered: bool,
    #[serde(default, rename = "numberFormat")]
    number_format: Option<NumberFormat>,
}

#[derive(Deserialize, Clone)]
pub struct NumberFormat {
    #[serde(default)]
    decimals: Option<i32>,
    #[serde(default)]
    percent: bool,
    #[serde(default, rename = "currencySymbol")]
    currency_symbol: Option<String>,
    #[serde(default)]
    grouping: bool,
    #[serde(default, rename = "isDate")]
    is_date: bool,
    #[serde(default, rename = "isTime")]
    is_time: bool,
    #[serde(default, rename = "isScientific")]
    is_scientific: bool,
    #[serde(default, rename = "isFraction")]
    is_fraction: bool,
    #[serde(default = "one_i32", rename = "fractionDenominatorDigits")]
    fraction_denominator_digits: i32,
    #[serde(default, rename = "dateTimeTokens")]
    date_time_tokens: Vec<NumberToken>,
}

fn one_i32() -> i32 {
    1
}

#[derive(Deserialize, Clone)]
pub struct NumberToken {
    #[serde(default)]
    kind: String,
    #[serde(default)]
    style: Option<String>,
    #[serde(default)]
    text: Option<String>,
    #[serde(default)]
    textual: bool,
}

struct Sheet {
    name: String,
    rows: Vec<Vec<Cell>>,
}

pub struct Workbook {
    sheets: Vec<Sheet>,
    name_to_idx: HashMap<String, usize>,
    now_millis: i64,
    rng: RefCell<u64>,
}

// ---- value model -----------------------------------------------------------

#[derive(Clone, Debug, PartialEq)]
enum Value {
    Num(f64),
    Str(String),
    Bool(bool),
    Err(String),
    Blank,
}

#[derive(Clone, Debug)]
struct EvalError(String);

type R<T> = Result<T, EvalError>;

fn ferr(code: &str) -> EvalError {
    EvalError(code.to_string())
}

/// A parsed reference: single cell (`is_range=false`) or rectangular range.
#[derive(Clone)]
struct Ref {
    r1: i32,
    c1: i32,
    r2: i32,
    c2: i32,
    is_range: bool,
    sheet: Option<String>,
}

#[derive(Clone)]
enum Arg {
    Scalar(Value),
    RangeRef {
        r1: i32,
        c1: i32,
        r2: i32,
        c2: i32,
        sheet: Option<String>,
    },
}

/// A lazily-evaluated function argument (re-parsed each access, like the Kotlin ArgThunk).
struct ArgThunk {
    text: String,
}

impl Workbook {
    pub fn from_json(json: &str, now_millis: i64) -> Option<Workbook> {
        let parsed: WorkbookJson = serde_json::from_str(json).ok()?;
        let mut sheets = Vec::with_capacity(parsed.sheets.len());
        let mut name_to_idx = HashMap::new();
        for (i, s) in parsed.sheets.into_iter().enumerate() {
            name_to_idx.entry(s.name.clone()).or_insert(i);
            let rows: Vec<Vec<Cell>> = s.rows.into_iter().map(|r| r.cells).collect();
            sheets.push(Sheet { name: s.name, rows });
        }
        // Seed the PRNG deterministically from now_millis (splitmix64-style).
        let seed = (now_millis as u64) ^ 0x9E3779B97F4A7C15;
        Some(Workbook {
            sheets,
            name_to_idx,
            now_millis,
            rng: RefCell::new(seed.max(1)),
        })
    }

    pub fn display_value(&self, sheet_idx: usize, row: i32, col: i32) -> String {
        let sheet = match self.sheets.get(sheet_idx) {
            Some(s) => s,
            None => return String::new(),
        };
        let cell = match cell_at(sheet, row, col) {
            Some(c) => c.clone(),
            None => return String::new(),
        };
        if cell.formula.is_none() {
            return cell.text;
        }
        let visiting = RefCell::new(HashSet::new());
        let mut ev = Evaluator {
            wb: self,
            sheet_idx,
            sheet_name: sheet.name.clone(),
            cur_row: 0,
            cur_col: 0,
            visiting: &visiting,
        };
        match ev.evaluate_cell_value(row, col) {
            Value::Num(v) => {
                if v.is_nan() {
                    "#ERR".to_string()
                } else {
                    format_with_style(v, cell.number_format.as_ref())
                }
            }
            Value::Bool(b) => {
                if b {
                    "TRUE".to_string()
                } else {
                    "FALSE".to_string()
                }
            }
            Value::Str(s) => s,
            Value::Err(code) => code,
            Value::Blank => String::new(),
        }
    }

    pub fn is_numeric(&self, sheet_idx: usize, row: i32, col: i32) -> bool {
        let sheet = match self.sheets.get(sheet_idx) {
            Some(s) => s,
            None => return false,
        };
        let cell = match cell_at(sheet, row, col) {
            Some(c) => c,
            None => return false,
        };
        if cell.number_value.is_some() {
            return true;
        }
        if let Some(vt) = &cell.value_type {
            if vt == "float" || vt == "percentage" || vt == "currency" {
                return true;
            }
        }
        if cell.formula.is_some() {
            let visiting = RefCell::new(HashSet::new());
            let mut ev = Evaluator {
                wb: self,
                sheet_idx,
                sheet_name: sheet.name.clone(),
                cur_row: 0,
                cur_col: 0,
                visiting: &visiting,
            };
            return match ev.evaluate_cell_value(row, col) {
                Value::Num(v) => !v.is_nan(),
                _ => false,
            };
        }
        parse_f64(&cell.text).is_some()
    }
}

fn cell_at(sheet: &Sheet, row: i32, col: i32) -> Option<&Cell> {
    if row < 0 || col < 0 {
        return None;
    }
    sheet
        .rows
        .get(row as usize)
        .and_then(|r| r.get(col as usize))
}

// ---- number / date formatting ---------------------------------------------

const MONTHS: [&str; 12] = [
    "January", "February", "March", "April", "May", "June", "July", "August", "September",
    "October", "November", "December",
];
const MONTHS_SHORT: [&str; 12] = [
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
];
const WEEKDAYS: [&str; 7] = [
    "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
];
const WEEKDAYS_SHORT: [&str; 7] = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

/// Java Math.round semantics: floor(x + 0.5) (rounds half toward +infinity).
fn math_round(x: f64) -> i64 {
    (x + 0.5).floor() as i64
}

/// Parses a double the way Kotlin's String.toDoubleOrNull() does for cell text.
fn parse_f64(s: &str) -> Option<f64> {
    let t = s.trim();
    if t.is_empty() {
        return None;
    }
    t.parse::<f64>().ok()
}

/// Fixed-decimal formatting matching Java String.format("%.Nf") (HALF_UP rounding),
/// with optional thousands grouping.
fn format_fixed(value: f64, decimals: usize, grouping: bool) -> String {
    if value.is_nan() {
        return "NaN".to_string();
    }
    if value.is_infinite() {
        return if value < 0.0 { "-Infinity" } else { "Infinity" }.to_string();
    }
    let neg = value.is_sign_negative() && value != 0.0;
    let av = value.abs();
    let scale = 10f64.powi(decimals as i32);
    // HALF_UP: round half away from zero.
    let scaled = (av * scale + 0.5).floor();
    let int_part0 = (scaled / scale).floor() as i128;
    let frac_units0 = (scaled - (int_part0 as f64) * scale) as i128;
    // Guard against fp drift pushing frac to full unit.
    let (int_part, frac_units) = if frac_units0 >= scale as i128 {
        (int_part0 + 1, frac_units0 - scale as i128)
    } else {
        (int_part0, frac_units0)
    };
    let mut int_str = int_part.to_string();
    if grouping {
        int_str = group_thousands(&int_str);
    }
    let mut out = String::new();
    if neg {
        out.push('-');
    }
    out.push_str(&int_str);
    if decimals > 0 {
        out.push('.');
        let fs = frac_units.to_string();
        for _ in 0..(decimals.saturating_sub(fs.len())) {
            out.push('0');
        }
        out.push_str(&fs);
    }
    out
}

fn group_thousands(digits: &str) -> String {
    let bytes = digits.as_bytes();
    let mut out = String::new();
    let len = bytes.len();
    for (i, b) in bytes.iter().enumerate() {
        if i > 0 && (len - i) % 3 == 0 {
            out.push(',');
        }
        out.push(*b as char);
    }
    out
}

/// Java String.format("%.NE") scientific formatting: e.g. 12345 -> "1.23E+04".
fn format_scientific(value: f64, decimals: usize) -> String {
    if value == 0.0 {
        let mant = format_fixed(0.0, decimals, false);
        return format!("{}E+00", mant);
    }
    let neg = value < 0.0;
    let mut av = value.abs();
    let mut exp = av.log10().floor() as i32;
    let mut mant = av / 10f64.powi(exp);
    // Rounding the mantissa can bump it to 10.0; renormalize.
    let scale = 10f64.powi(decimals as i32);
    let rounded = (mant * scale + 0.5).floor() / scale;
    if rounded >= 10.0 {
        exp += 1;
        av = value.abs();
        mant = av / 10f64.powi(exp);
    } else {
        mant = rounded;
    }
    let mant_str = format_fixed(mant, decimals, false);
    let sign = if exp < 0 { '-' } else { '+' };
    let ea = exp.abs();
    let exp_str = if ea < 10 {
        format!("0{}", ea)
    } else {
        ea.to_string()
    };
    format!("{}{}E{}{}", if neg { "-" } else { "" }, mant_str, sign, exp_str)
}

pub fn format_number(v: f64) -> String {
    if v.is_nan() {
        return "#ERR".to_string();
    }
    if v.is_infinite() {
        return "#DIV/0!".to_string();
    }
    if v == (v as i64) as f64 {
        return (v as i64).to_string();
    }
    let s = format_fixed(v, 4, false);
    let trimmed = s.trim_end_matches('0');
    let trimmed = trimmed.trim_end_matches('.');
    trimmed.to_string()
}

/// Standalone number formatter for callers with no workbook context (e.g. XLSX
/// cell display). `nf_json` is a serialized [NumberFormat] (same schema as a
/// workbook cell's `numberFormat`); `"null"` or empty means "no format".
pub fn format_value_json(value: f64, nf_json: &str) -> String {
    let trimmed = nf_json.trim();
    let fmt: Option<NumberFormat> = if trimmed.is_empty() || trimmed == "null" {
        None
    } else {
        serde_json::from_str(trimmed).ok()
    };
    format_with_style(value, fmt.as_ref())
}

fn format_with_style(v: f64, fmt: Option<&NumberFormat>) -> String {
    let fmt = match fmt {
        Some(f) => f,
        None => return format_number(v),
    };
    if !fmt.date_time_tokens.is_empty() {
        return format_date_time(v, &fmt.date_time_tokens);
    }
    if fmt.is_date {
        return format_date_iso(v);
    }
    if fmt.is_time {
        return format_time(v);
    }
    if fmt.is_scientific {
        let decimals = fmt.decimals.unwrap_or(2).clamp(0, 10) as usize;
        return format_scientific(v, decimals);
    }
    if fmt.is_fraction {
        return format_fraction(v, fmt.fraction_denominator_digits);
    }
    let mut value = v;
    if fmt.percent {
        value *= 100.0;
    }
    let decimals = fmt.decimals.unwrap_or(2).clamp(0, 10) as usize;
    let mut s = format_fixed(value, decimals, fmt.grouping);
    if fmt.percent {
        s.push('%');
    }
    if let Some(cur) = &fmt.currency_symbol {
        s = format!("{}{}", cur, s);
    }
    s
}

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
    let yoe = (y - era * 400) as i64; // [0, 399]
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

impl<'a> Evaluator<'a> {
    fn sheet(&self) -> &'a Sheet {
        &self.wb.sheets[self.sheet_idx]
    }

    fn evaluate_cell_value(&mut self, row: i32, col: i32) -> Value {
        match self.evaluate_cell_v(row, col) {
            Ok(v) => v,
            Err(e) => Value::Err(e.0),
        }
    }

    /// Resolves a (possibly cross-sheet) cell reference, evaluating on the target sheet.
    fn eval_on(&mut self, name: &Option<String>, row: i32, col: i32) -> R<Value> {
        match name {
            None => self.evaluate_cell_v(row, col),
            Some(n) if *n == self.sheet_name => self.evaluate_cell_v(row, col),
            Some(n) => {
                let idx = match self.wb.name_to_idx.get(n) {
                    Some(i) => *i,
                    None => return Err(ferr("#REF!")),
                };
                let mut ev = Evaluator {
                    wb: self.wb,
                    sheet_idx: idx,
                    sheet_name: n.clone(),
                    cur_row: 0,
                    cur_col: 0,
                    visiting: self.visiting,
                };
                ev.evaluate_cell_v(row, col)
            }
        }
    }

    fn evaluate_cell_v(&mut self, row: i32, col: i32) -> R<Value> {
        let key = format!("{}!{},{}", self.sheet_name, row, col);
        if self.visiting.borrow().contains(&key) {
            return Err(ferr("#REF!"));
        }
        let cell = match cell_at(self.sheet(), row, col) {
            Some(c) => c.clone(),
            None => return Ok(Value::Blank),
        };
        let formula = match &cell.formula {
            Some(f) => f.clone(),
            None => return Ok(raw_cell_value(&cell)),
        };
        self.visiting.borrow_mut().insert(key.clone());
        let save_r = self.cur_row;
        let save_c = self.cur_col;
        self.cur_row = row;
        self.cur_col = col;
        let expr = normalize(&formula);
        let mut parser = Parser::new(&expr);
        let result = parser.parse_expression(self);
        self.visiting.borrow_mut().remove(&key);
        self.cur_row = save_r;
        self.cur_col = save_c;
        result
    }

    // ---- coercions ---------------------------------------------------------

    fn num(&self, v: Value) -> R<f64> {
        match v {
            Value::Num(x) => Ok(x),
            Value::Bool(b) => Ok(if b { 1.0 } else { 0.0 }),
            Value::Str(s) => parse_f64(&s).ok_or_else(|| ferr("#VALUE!")),
            Value::Blank => Ok(0.0),
            Value::Err(c) => Err(EvalError(c)),
        }
    }

    fn num_or_null(&self, v: &Value) -> Option<f64> {
        match v {
            Value::Num(x) => Some(*x),
            Value::Bool(b) => Some(if *b { 1.0 } else { 0.0 }),
            _ => None,
        }
    }

    fn str_of(&self, v: Value) -> R<String> {
        match v {
            Value::Num(x) => Ok(format_number(x)),
            Value::Bool(b) => Ok(if b { "TRUE".into() } else { "FALSE".into() }),
            Value::Str(s) => Ok(s),
            Value::Blank => Ok(String::new()),
            Value::Err(c) => Err(EvalError(c)),
        }
    }

    fn truthy(&self, v: Value) -> R<bool> {
        match v {
            Value::Bool(b) => Ok(b),
            Value::Num(x) => Ok(x != 0.0),
            Value::Str(s) => {
                if s.eq_ignore_ascii_case("TRUE") {
                    Ok(true)
                } else if s.eq_ignore_ascii_case("FALSE") {
                    Ok(false)
                } else {
                    match parse_f64(&s) {
                        Some(x) => Ok(x != 0.0),
                        None => Err(ferr("#VALUE!")),
                    }
                }
            }
            Value::Blank => Ok(false),
            Value::Err(c) => Err(EvalError(c)),
        }
    }

    // ---- range / arg helpers ----------------------------------------------

    fn range_values(
        &mut self,
        r1: i32,
        c1: i32,
        r2: i32,
        c2: i32,
        sheet_ref: &Option<String>,
    ) -> R<Vec<Value>> {
        let mut out = Vec::new();
        let target_idx = match sheet_ref {
            None => self.sheet_idx,
            Some(n) if *n == self.sheet_name => self.sheet_idx,
            Some(n) => *self.wb.name_to_idx.get(n).unwrap_or(&self.sheet_idx),
        };
        for r in r1.min(r2)..=r1.max(r2) {
            for c in c1.min(c2)..=c1.max(c2) {
                if let Some(cell) = cell_at(&self.wb.sheets[target_idx], r, c) {
                    if cell.is_covered {
                        continue;
                    }
                }
                out.push(self.eval_on(sheet_ref, r, c)?);
            }
        }
        Ok(out)
    }

    fn arg_values(&mut self, arg: &Arg) -> R<Vec<Value>> {
        match arg {
            Arg::Scalar(v) => Ok(vec![v.clone()]),
            Arg::RangeRef {
                r1,
                c1,
                r2,
                c2,
                sheet,
            } => self.range_values(*r1, *c1, *r2, *c2, sheet),
        }
    }

    fn cell_at_ref(&self, sheet_ref: &Option<String>, r: i32, c: i32) -> Option<Cell> {
        let idx = match sheet_ref {
            None => self.sheet_idx,
            Some(n) if *n == self.sheet_name => self.sheet_idx,
            Some(n) => *self.wb.name_to_idx.get(n).unwrap_or(&self.sheet_idx),
        };
        cell_at(&self.wb.sheets[idx], r, c).cloned()
    }

    // ---- thunk accessors ---------------------------------------------------

    fn t_arg(&mut self, t: &ArgThunk) -> R<Arg> {
        let mut p = Parser::new(&t.text);
        p.parse_arg_top(self)
    }

    fn t_value(&mut self, t: &ArgThunk) -> R<Value> {
        let a = self.t_arg(t)?;
        match a {
            Arg::Scalar(v) => Ok(v),
            Arg::RangeRef { r1, c1, sheet, .. } => self.eval_on(&sheet, r1, c1),
        }
    }

    fn t_values(&mut self, t: &ArgThunk) -> R<Vec<Value>> {
        let a = self.t_arg(t)?;
        self.arg_values(&a)
    }

    fn t_ref(&mut self, t: &ArgThunk) -> Option<Ref> {
        let mut p = Parser::new(&t.text);
        p.parse_ref_top()
    }

    fn t_num(&mut self, t: &ArgThunk) -> R<f64> {
        let v = self.t_value(t)?;
        self.num(v)
    }

    fn t_truthy(&mut self, t: &ArgThunk) -> R<bool> {
        let v = self.t_value(t)?;
        self.truthy(v)
    }

    fn t_str(&mut self, t: &ArgThunk) -> R<String> {
        let v = self.t_value(t)?;
        self.str_of(v)
    }

    fn all_vals(&mut self, a: &[ArgThunk]) -> R<Vec<Value>> {
        let mut out = Vec::new();
        for t in a {
            out.extend(self.t_values(t)?);
        }
        Ok(out)
    }

    fn all_nums(&mut self, a: &[ArgThunk]) -> R<Vec<f64>> {
        let vals = self.all_vals(a)?;
        Ok(vals.iter().filter_map(|v| self.num_or_null(v)).collect())
    }

    // ---- comparison --------------------------------------------------------

    fn cmp_num(&self, v: &Value) -> R<Option<f64>> {
        match v {
            Value::Num(x) => Ok(Some(*x)),
            Value::Bool(b) => Ok(Some(if *b { 1.0 } else { 0.0 })),
            Value::Blank => Ok(Some(0.0)),
            Value::Err(c) => Err(EvalError(c.clone())),
            Value::Str(_) => Ok(None),
        }
    }

    fn compare_op(&self, op: &str, l: &Value, r: &Value) -> R<bool> {
        let ln = self.cmp_num(l)?;
        let rn = self.cmp_num(r)?;
        if let (Some(a), Some(b)) = (ln, rn) {
            return Ok(match op {
                "<" => a < b,
                "<=" => a <= b,
                ">" => a > b,
                ">=" => a >= b,
                "=" => a == b,
                "<>" => a != b,
                _ => false,
            });
        }
        let ls = self.str_of(l.clone())?;
        let rs = self.str_of(r.clone())?;
        let cmp = cmp_ignore_case(&ls, &rs);
        Ok(match op {
            "<" => cmp < 0,
            "<=" => cmp <= 0,
            ">" => cmp > 0,
            ">=" => cmp >= 0,
            "=" => cmp == 0,
            "<>" => cmp != 0,
            _ => false,
        })
    }

    // ---- criteria (SUMIF/COUNTIF/...) -------------------------------------

    fn matches_criteria(&self, v: &Value, criteria: &Value) -> R<bool> {
        let crit = self.str_of(criteria.clone())?;
        let mut op = "=";
        let mut rest = crit.trim().to_string();
        for o in ["<=", ">=", "<>", "<", ">", "="] {
            if rest.starts_with(o) {
                op = o;
                rest = rest[o.len()..].trim().to_string();
                break;
            }
        }
        let rest_num = parse_f64(&rest);
        let v_num = self.num_or_null(v);
        if let (Some(rn), Some(vn)) = (rest_num, v_num) {
            return Ok(match op {
                "<" => vn < rn,
                "<=" => vn <= rn,
                ">" => vn > rn,
                ">=" => vn >= rn,
                "<>" => vn != rn,
                _ => vn == rn,
            });
        }
        let v_str = if *v == Value::Blank {
            String::new()
        } else {
            self.str_of(v.clone())?
        };
        Ok(match op {
            "<>" => !wildcard_equals(&v_str, &rest),
            "=" => wildcard_equals(&v_str, &rest),
            _ => false,
        })
    }
}

fn raw_cell_value(cell: &Cell) -> Value {
    if let Some(n) = cell.number_value {
        return Value::Num(n);
    }
    let t = &cell.text;
    if t.is_empty() {
        return Value::Blank;
    }
    if let Some(n) = parse_f64(t) {
        return Value::Num(n);
    }
    if t.eq_ignore_ascii_case("TRUE") {
        return Value::Bool(true);
    }
    if t.eq_ignore_ascii_case("FALSE") {
        return Value::Bool(false);
    }
    Value::Str(t.clone())
}

fn normalize(formula: &str) -> String {
    let mut f = formula.trim().to_string();
    if let Some(rest) = f.strip_prefix("of:") {
        f = rest.to_string();
    }
    if let Some(rest) = f.strip_prefix('=') {
        f = rest.to_string();
    }
    f
}

/// Case-insensitive comparison mirroring Kotlin String.compareTo(other, ignoreCase=true).
fn cmp_ignore_case(a: &str, b: &str) -> i32 {
    let mut ai = a.chars();
    let mut bi = b.chars();
    loop {
        match (ai.next(), bi.next()) {
            (Some(x), Some(y)) => {
                let xl = x.to_ascii_uppercase();
                let yl = y.to_ascii_uppercase();
                if xl != yl {
                    return (xl as i32) - (yl as i32);
                }
            }
            (None, None) => return 0,
            (None, Some(_)) => return -1,
            (Some(_), None) => return 1,
        }
    }
}

fn wildcard_equals(value: &str, pattern: &str) -> bool {
    if !pattern.contains('*') && !pattern.contains('?') {
        return value.eq_ignore_ascii_case(pattern);
    }
    let v: Vec<char> = value.chars().map(|c| c.to_ascii_uppercase()).collect();
    let p: Vec<char> = pattern.chars().map(|c| c.to_ascii_uppercase()).collect();
    // Iterative wildcard match with '*' and '?'.
    let (mut i, mut j) = (0usize, 0usize);
    let (mut star, mut mark) = (usize::MAX, 0usize);
    while i < v.len() {
        if j < p.len() && (p[j] == '?' || p[j] == v[i]) {
            i += 1;
            j += 1;
        } else if j < p.len() && p[j] == '*' {
            star = j;
            mark = i;
            j += 1;
        } else if star != usize::MAX {
            j = star + 1;
            mark += 1;
            i = mark;
        } else {
            return false;
        }
    }
    while j < p.len() && p[j] == '*' {
        j += 1;
    }
    j == p.len()
}

// ---- column helpers --------------------------------------------------------

fn col_to_index(col: &str) -> i32 {
    let mut n = 0i32;
    for c in col.chars() {
        if c.is_ascii_alphabetic() {
            n = n * 26 + (c.to_ascii_uppercase() as i32 - 'A' as i32 + 1);
        }
    }
    n - 1
}

fn index_to_col(index: i32) -> String {
    if index < 0 {
        return "A".to_string();
    }
    let mut n = index + 1;
    let mut chars = Vec::new();
    while n > 0 {
        let rem = (n - 1) % 26;
        chars.push((b'A' + rem as u8) as char);
        n = (n - 1) / 26;
    }
    chars.iter().rev().collect()
}

// ---- recursive-descent parser ----------------------------------------------

struct Parser {
    s: Vec<char>,
    pos: usize,
}

impl Parser {
    fn new(text: &str) -> Parser {
        Parser {
            s: text.chars().collect(),
            pos: 0,
        }
    }

    fn peek(&self) -> char {
        if self.pos < self.s.len() {
            self.s[self.pos]
        } else {
            '\0'
        }
    }

    fn skip_ws(&mut self) {
        while self.pos < self.s.len() && self.s[self.pos].is_whitespace() {
            self.pos += 1;
        }
    }

    fn match_token(&mut self, token: &str) -> bool {
        self.skip_ws();
        let tc: Vec<char> = token.chars().collect();
        if self.pos + tc.len() <= self.s.len() && self.s[self.pos..self.pos + tc.len()] == tc[..] {
            self.pos += tc.len();
            true
        } else {
            false
        }
    }

    fn substr(&self, start: usize, end: usize) -> String {
        self.s[start..end].iter().collect()
    }

    fn parse_expression(&mut self, ev: &mut Evaluator) -> R<Value> {
        self.parse_comparison(ev)
    }

    fn parse_arg_top(&mut self, ev: &mut Evaluator) -> R<Arg> {
        self.skip_ws();
        if self.peek() == '[' {
            let save = self.pos;
            let r = self.parse_ref_raw();
            self.skip_ws();
            if self.pos >= self.s.len() {
                return if r.is_range {
                    Ok(Arg::RangeRef {
                        r1: r.r1,
                        c1: r.c1,
                        r2: r.r2,
                        c2: r.c2,
                        sheet: r.sheet,
                    })
                } else {
                    Ok(Arg::Scalar(ev.eval_on(&r.sheet, r.r1, r.c1)?))
                };
            }
            self.pos = save;
        }
        Ok(Arg::Scalar(self.parse_expression(ev)?))
    }

    fn parse_ref_top(&mut self) -> Option<Ref> {
        self.skip_ws();
        if self.peek() != '[' {
            return None;
        }
        let r = self.parse_ref_raw();
        self.skip_ws();
        if self.pos >= self.s.len() {
            Some(r)
        } else {
            None
        }
    }

    fn parse_comparison(&mut self, ev: &mut Evaluator) -> R<Value> {
        let mut left = self.parse_concat(ev)?;
        loop {
            self.skip_ws();
            let op = if self.match_token("<=") {
                "<="
            } else if self.match_token(">=") {
                ">="
            } else if self.match_token("<>") {
                "<>"
            } else if self.peek() == '<' {
                self.pos += 1;
                "<"
            } else if self.peek() == '>' {
                self.pos += 1;
                ">"
            } else if self.peek() == '=' {
                self.pos += 1;
                "="
            } else {
                return Ok(left);
            };
            let right = self.parse_concat(ev)?;
            left = Value::Bool(ev.compare_op(op, &left, &right)?);
        }
    }

    fn parse_concat(&mut self, ev: &mut Evaluator) -> R<Value> {
        let mut v = self.parse_add_sub(ev)?;
        loop {
            self.skip_ws();
            if self.peek() == '&' {
                self.pos += 1;
                let rhs = self.parse_add_sub(ev)?;
                let ls = ev.str_of(v)?;
                let rs = ev.str_of(rhs)?;
                v = Value::Str(ls + &rs);
            } else {
                return Ok(v);
            }
        }
    }

    fn parse_add_sub(&mut self, ev: &mut Evaluator) -> R<Value> {
        let mut v = self.parse_mul_div(ev)?;
        loop {
            self.skip_ws();
            match self.peek() {
                '+' => {
                    self.pos += 1;
                    let a = ev.num(v)?;
                    let rhs = self.parse_mul_div(ev)?;
                    let b = ev.num(rhs)?;
                    v = Value::Num(a + b);
                }
                '-' => {
                    self.pos += 1;
                    let a = ev.num(v)?;
                    let rhs = self.parse_mul_div(ev)?;
                    let b = ev.num(rhs)?;
                    v = Value::Num(a - b);
                }
                _ => return Ok(v),
            }
        }
    }

    fn parse_mul_div(&mut self, ev: &mut Evaluator) -> R<Value> {
        let mut v = self.parse_power(ev)?;
        loop {
            self.skip_ws();
            match self.peek() {
                '*' => {
                    self.pos += 1;
                    let a = ev.num(v)?;
                    let rhs = self.parse_power(ev)?;
                    let b = ev.num(rhs)?;
                    v = Value::Num(a * b);
                }
                '/' => {
                    self.pos += 1;
                    let a = ev.num(v)?;
                    let rhs = self.parse_power(ev)?;
                    let d = ev.num(rhs)?;
                    if d == 0.0 {
                        return Err(ferr("#DIV/0!"));
                    }
                    v = Value::Num(a / d);
                }
                _ => return Ok(v),
            }
        }
    }

    fn parse_power(&mut self, ev: &mut Evaluator) -> R<Value> {
        let base = self.parse_unary(ev)?;
        self.skip_ws();
        if self.peek() == '^' {
            self.pos += 1;
            let a = ev.num(base)?;
            let rhs = self.parse_unary(ev)?;
            let b = ev.num(rhs)?;
            return Ok(Value::Num(a.powf(b)));
        }
        Ok(base)
    }

    fn parse_unary(&mut self, ev: &mut Evaluator) -> R<Value> {
        self.skip_ws();
        if self.peek() == '-' {
            self.pos += 1;
            let v = self.parse_unary(ev)?;
            return Ok(Value::Num(-ev.num(v)?));
        }
        if self.peek() == '+' {
            self.pos += 1;
            return self.parse_unary(ev);
        }
        self.parse_primary(ev)
    }

    fn parse_primary(&mut self, ev: &mut Evaluator) -> R<Value> {
        self.skip_ws();
        let c = self.peek();
        if c == '(' {
            self.pos += 1;
            let v = self.parse_expression(ev)?;
            self.skip_ws();
            if self.peek() == ')' {
                self.pos += 1;
            }
            Ok(v)
        } else if c == '[' {
            let r = self.parse_ref_raw();
            ev.eval_on(&r.sheet, r.r1, r.c1)
        } else if c == '"' {
            Ok(Value::Str(self.parse_string()))
        } else if c.is_ascii_digit() || c == '.' {
            Ok(Value::Num(self.parse_number()))
        } else if c.is_alphabetic() {
            self.parse_function_or_const(ev)
        } else {
            self.pos += 1;
            Ok(Value::Num(0.0))
        }
    }

    fn parse_number(&mut self) -> f64 {
        let start = self.pos;
        while self.pos < self.s.len() {
            let c = self.s[self.pos];
            if c.is_ascii_digit() || c == '.' || c == 'E' || c == 'e' {
                self.pos += 1;
            } else {
                break;
            }
        }
        let mut value = self.substr(start, self.pos).parse::<f64>().unwrap_or(0.0);
        self.skip_ws();
        if self.peek() == '%' {
            self.pos += 1;
            value /= 100.0;
        }
        value
    }

    fn parse_string(&mut self) -> String {
        self.pos += 1; // opening quote
        let mut sb = String::new();
        while self.pos < self.s.len() {
            let ch = self.s[self.pos];
            if ch == '"' {
                if self.pos + 1 < self.s.len() && self.s[self.pos + 1] == '"' {
                    sb.push('"');
                    self.pos += 2;
                    continue;
                }
                self.pos += 1;
                break;
            }
            sb.push(ch);
            self.pos += 1;
        }
        sb
    }

    fn parse_ref_raw(&mut self) -> Ref {
        self.pos += 1; // '['
        let mut sb = String::new();
        while self.pos < self.s.len() && self.s[self.pos] != ']' {
            sb.push(self.s[self.pos]);
            self.pos += 1;
        }
        if self.pos < self.s.len() {
            self.pos += 1; // ']'
        }
        let raw = sb.replace('$', "");
        let first_endpoint = substring_before(&raw, ":");
        let sheet_ref = {
            let before_dot = substring_before_last(&first_endpoint, '.', "");
            let s = before_dot.trim_start_matches('.').trim().trim_matches('\'');
            if s.is_empty() {
                None
            } else {
                Some(s.to_string())
            }
        };
        if raw.contains(':') {
            let mut parts = raw.splitn(2, ':');
            let a = parts.next().unwrap_or("");
            let b = parts.next().unwrap_or("");
            let (r1, c1) = parse_cell_coords(a);
            let (r2, c2) = parse_cell_coords(b);
            Ref {
                r1,
                c1,
                r2,
                c2,
                is_range: true,
                sheet: sheet_ref,
            }
        } else {
            let (r, c) = parse_cell_coords(&raw);
            Ref {
                r1: r,
                c1: c,
                r2: r,
                c2: c,
                is_range: false,
                sheet: sheet_ref,
            }
        }
    }

    fn parse_function_or_const(&mut self, ev: &mut Evaluator) -> R<Value> {
        let start = self.pos;
        while self.pos < self.s.len() {
            let c = self.s[self.pos];
            if c.is_alphanumeric() || c == '_' || c == '.' {
                self.pos += 1;
            } else {
                break;
            }
        }
        let mut name = self.substr(start, self.pos).to_uppercase();
        if let Some(rest) = name.strip_prefix("ORG.OPENOFFICE.") {
            name = rest.to_string();
        }
        if let Some(rest) = name.strip_prefix("COM.MICROSOFT.") {
            name = rest.to_string();
        }
        self.skip_ws();
        if self.peek() != '(' {
            return match name.as_str() {
                "TRUE" => Ok(Value::Bool(true)),
                "FALSE" => Ok(Value::Bool(false)),
                "PI" => Ok(Value::Num(std::f64::consts::PI)),
                _ => Err(ferr("#NAME?")),
            };
        }
        self.pos += 1; // '('
        let arg_texts = self.split_args();
        let thunks: Vec<ArgThunk> = arg_texts.into_iter().map(|t| ArgThunk { text: t }).collect();
        ev.apply_function(&name, &thunks)
    }

    fn split_args(&mut self) -> Vec<String> {
        let mut parts: Vec<String> = Vec::new();
        let mut cur = String::new();
        let mut depth = 0i32;
        let mut in_str = false;
        while self.pos < self.s.len() {
            let c = self.s[self.pos];
            if in_str {
                cur.push(c);
                if c == '"' {
                    in_str = false;
                }
                self.pos += 1;
                continue;
            }
            match c {
                '"' => {
                    in_str = true;
                    cur.push(c);
                    self.pos += 1;
                }
                '(' | '[' => {
                    depth += 1;
                    cur.push(c);
                    self.pos += 1;
                }
                ']' => {
                    depth -= 1;
                    cur.push(c);
                    self.pos += 1;
                }
                ')' => {
                    if depth == 0 {
                        self.pos += 1;
                        break;
                    } else {
                        depth -= 1;
                        cur.push(c);
                        self.pos += 1;
                    }
                }
                ',' | ';' => {
                    if depth == 0 {
                        parts.push(cur.clone());
                        cur.clear();
                        self.pos += 1;
                    } else {
                        cur.push(c);
                        self.pos += 1;
                    }
                }
                _ => {
                    cur.push(c);
                    self.pos += 1;
                }
            }
        }
        if !cur.trim().is_empty() || !parts.is_empty() {
            parts.push(cur);
        }
        parts
    }
}

fn parse_cell_coords(token: &str) -> (i32, i32) {
    let t = substring_after_last(token, '.');
    // split leading letters then digits: ([A-Za-z]+)(\d+)
    let chars: Vec<char> = t.chars().collect();
    let mut i = 0;
    // skip to first letter
    while i < chars.len() && !chars[i].is_ascii_alphabetic() {
        i += 1;
    }
    let letter_start = i;
    while i < chars.len() && chars[i].is_ascii_alphabetic() {
        i += 1;
    }
    let letters: String = chars[letter_start..i].iter().collect();
    let digit_start = i;
    while i < chars.len() && chars[i].is_ascii_digit() {
        i += 1;
    }
    let digits: String = chars[digit_start..i].iter().collect();
    if letters.is_empty() || digits.is_empty() {
        return (0, 0);
    }
    let col = col_to_index(&letters);
    let row = digits.parse::<i32>().unwrap_or(1) - 1;
    (row, col)
}

// ---- Kotlin-style substring helpers ----------------------------------------

fn substring_before<'a>(s: &'a str, delim: &str) -> String {
    match s.find(delim) {
        Some(i) => s[..i].to_string(),
        None => s.to_string(),
    }
}

fn substring_before_last(s: &str, delim: char, missing: &str) -> String {
    match s.rfind(delim) {
        Some(i) => s[..i].to_string(),
        None => missing.to_string(),
    }
}

fn substring_after_last(s: &str, delim: char) -> String {
    match s.rfind(delim) {
        Some(i) => s[i + delim.len_utf8()..].to_string(),
        None => s.to_string(),
    }
}

// ---- extra coercion helpers ------------------------------------------------

fn ksign(x: f64) -> f64 {
    if x.is_nan() {
        x
    } else if x > 0.0 {
        1.0
    } else if x < 0.0 {
        -1.0
    } else {
        0.0
    }
}

fn avg_of(list: &[f64]) -> f64 {
    if list.is_empty() {
        f64::NAN
    } else {
        list.iter().sum::<f64>() / list.len() as f64
    }
}

fn fact_d(k: i64) -> f64 {
    let mut r = 1.0;
    let mut i = 2;
    while i <= k {
        r *= i as f64;
        i += 1;
    }
    r
}

fn gcd_l(x: i64, y: i64) -> i64 {
    if y == 0 {
        x.abs()
    } else {
        gcd_l(y, x % y)
    }
}

fn roman_to_arabic(roman: &str) -> i32 {
    let map = |c: char| match c {
        'I' => 1,
        'V' => 5,
        'X' => 10,
        'L' => 50,
        'C' => 100,
        'D' => 500,
        'M' => 1000,
        _ => 0,
    };
    let s: Vec<char> = roman.trim().to_uppercase().chars().collect();
    let mut total = 0;
    for i in 0..s.len() {
        let cur = map(s[i]);
        if cur == 0 {
            continue;
        }
        let next = if i + 1 < s.len() { map(s[i + 1]) } else { 0 };
        if cur < next {
            total -= cur;
        } else {
            total += cur;
        }
    }
    total
}

fn arabic_to_roman(value: i32) -> String {
    if value <= 0 || value >= 4000 {
        return value.to_string();
    }
    let nums = [1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1];
    let syms = [
        "M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I",
    ];
    let mut v = value;
    let mut sb = String::new();
    for i in 0..nums.len() {
        while v >= nums[i] {
            sb.push_str(syms[i]);
            v -= nums[i];
        }
    }
    sb
}

impl<'a> Evaluator<'a> {
    fn t_nums(&mut self, t: &ArgThunk) -> R<Vec<f64>> {
        let vs = self.t_values(t)?;
        Ok(vs.iter().filter_map(|v| self.num_or_null(v)).collect())
    }

    fn vals_a(&mut self, a: &[ArgThunk]) -> R<Vec<f64>> {
        let vals = self.all_vals(a)?;
        let mut out = Vec::new();
        for v in vals {
            match v {
                Value::Num(x) => out.push(x),
                Value::Bool(b) => out.push(if b { 1.0 } else { 0.0 }),
                Value::Str(_) => out.push(0.0),
                Value::Blank => {}
                Value::Err(c) => return Err(EvalError(c)),
            }
        }
        Ok(out)
    }

    fn now_serial(&self, with_time: bool) -> f64 {
        let raw = (self.wb.now_millis as f64 + EPOCH_OFFSET_DAYS as f64 * MS_PER_DAY) / MS_PER_DAY;
        if with_time {
            raw
        } else {
            raw.floor()
        }
    }

    fn next_rand(&self) -> f64 {
        let mut r = self.wb.rng.borrow_mut();
        let mut x = *r;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        *r = x;
        (x >> 11) as f64 / (1u64 << 53) as f64
    }

    fn pair(&mut self, a: &[ArgThunk], i: usize, j: usize) -> R<(Vec<f64>, Vec<f64>)> {
        let x = self.t_nums(&a[i])?;
        let y = self.t_nums(&a[j])?;
        let m = x.len().min(y.len());
        Ok((x[..m].to_vec(), y[..m].to_vec()))
    }

    fn apply_function(&mut self, name: &str, a: &[ArgThunk]) -> R<Value> {
        match name {
            // ---- math / statistics ----
            "SUM" => Ok(Value::Num(self.all_nums(a)?.iter().sum())),
            "AVERAGE" => {
                let ns = self.all_nums(a)?;
                if ns.is_empty() {
                    Err(ferr("#DIV/0!"))
                } else {
                    Ok(Value::Num(avg_of(&ns)))
                }
            }
            "MIN" => {
                let ns = self.all_nums(a)?;
                Ok(Value::Num(pipe_min(&ns)))
            }
            "MAX" => {
                let ns = self.all_nums(a)?;
                Ok(Value::Num(pipe_max(&ns)))
            }
            "COUNT" => Ok(Value::Num(self.all_nums(a)?.len() as f64)),
            "COUNTA" => {
                let vs = self.all_vals(a)?;
                Ok(Value::Num(vs.iter().filter(|v| **v != Value::Blank).count() as f64))
            }
            "COUNTBLANK" => {
                let vs = self.all_vals(a)?;
                Ok(Value::Num(vs.iter().filter(|v| **v == Value::Blank).count() as f64))
            }
            "PRODUCT" => {
                let ns = self.all_nums(a)?;
                if ns.is_empty() {
                    Ok(Value::Num(0.0))
                } else {
                    Ok(Value::Num(ns.iter().fold(1.0, |x, y| x * y)))
                }
            }
            "ABS" => Ok(Value::Num(self.t_num(&a[0])?.abs())),
            "SQRT" => Ok(Value::Num(self.t_num(&a[0])?.sqrt())),
            "POWER" => Ok(Value::Num(self.t_num(&a[0])?.powf(self.t_num(&a[1])?))),
            "MOD" => {
                let b = self.t_num(&a[1])?;
                if b == 0.0 {
                    Err(ferr("#DIV/0!"))
                } else {
                    let x = self.t_num(&a[0])?;
                    Ok(Value::Num(x - (x / b).floor() * b))
                }
            }
            "INT" => Ok(Value::Num(self.t_num(&a[0])?.floor())),
            "TRUNC" => Ok(Value::Num(self.t_num(&a[0])?.trunc())),
            "SIGN" => Ok(Value::Num(ksign(self.t_num(&a[0])?))),
            "EXP" => Ok(Value::Num(self.t_num(&a[0])?.exp())),
            "LN" => Ok(Value::Num(self.t_num(&a[0])?.ln())),
            "LOG10" => Ok(Value::Num(self.t_num(&a[0])?.log10())),
            "LOG" => {
                let x = self.t_num(&a[0])?;
                let base = if a.len() > 1 { self.t_num(&a[1])? } else { 10.0 };
                Ok(Value::Num(x.ln() / base.ln()))
            }
            "ROUND" => {
                let d = if a.len() > 1 { self.t_num(&a[1])? as i32 } else { 0 };
                let f = 10f64.powi(d);
                let x = self.t_num(&a[0])?;
                Ok(Value::Num(math_round(x * f) as f64 / f))
            }
            "ROUNDUP" => {
                let d = if a.len() > 1 { self.t_num(&a[1])? as i32 } else { 0 };
                let f = 10f64.powi(d);
                let x = self.t_num(&a[0])?;
                Ok(Value::Num((x.abs() * f).ceil() / f * (if x < 0.0 { -1.0 } else { 1.0 })))
            }
            "ROUNDDOWN" => {
                let d = if a.len() > 1 { self.t_num(&a[1])? as i32 } else { 0 };
                let f = 10f64.powi(d);
                let x = self.t_num(&a[0])?;
                Ok(Value::Num((x.abs() * f).floor() / f * (if x < 0.0 { -1.0 } else { 1.0 })))
            }
            "CEILING" => {
                let step = if a.len() > 1 { self.t_num(&a[1])? } else { 1.0 };
                if step == 0.0 {
                    Ok(Value::Num(0.0))
                } else {
                    Ok(Value::Num((self.t_num(&a[0])? / step).ceil() * step))
                }
            }
            "FLOOR" => {
                let step = if a.len() > 1 { self.t_num(&a[1])? } else { 1.0 };
                if step == 0.0 {
                    Ok(Value::Num(0.0))
                } else {
                    Ok(Value::Num((self.t_num(&a[0])? / step).floor() * step))
                }
            }
            "MEDIAN" => {
                let mut ns = self.all_nums(a)?;
                if ns.is_empty() {
                    return Err(ferr("#NUM!"));
                }
                ns.sort_by(|x, y| x.total_cmp(y));
                let sz = ns.len();
                Ok(Value::Num(if sz % 2 == 1 {
                    ns[sz / 2]
                } else {
                    (ns[sz / 2 - 1] + ns[sz / 2]) / 2.0
                }))
            }
            "STDEV" => {
                let ns = self.all_nums(a)?;
                if ns.len() < 2 {
                    Err(ferr("#DIV/0!"))
                } else {
                    let m = avg_of(&ns);
                    Ok(Value::Num(
                        (ns.iter().map(|x| (x - m).powi(2)).sum::<f64>() / (ns.len() - 1) as f64).sqrt(),
                    ))
                }
            }
            "VAR" => {
                let ns = self.all_nums(a)?;
                if ns.len() < 2 {
                    Err(ferr("#DIV/0!"))
                } else {
                    let m = avg_of(&ns);
                    Ok(Value::Num(
                        ns.iter().map(|x| (x - m).powi(2)).sum::<f64>() / (ns.len() - 1) as f64,
                    ))
                }
            }

            // ---- conditional aggregation ----
            "SUMIF" => self.conditional_agg(a, true, |vals| vals.iter().sum()),
            "AVERAGEIF" => self.conditional_agg(a, true, |vals| {
                if vals.is_empty() {
                    f64::NAN
                } else {
                    avg_of(vals)
                }
            }),
            "COUNTIF" => {
                let rng = self.t_arg(&a[0])?;
                let crit = self.t_value(&a[1])?;
                let mut count = 0;
                let cells = self.for_each_range_cell(&rng)?;
                for cv in cells {
                    if self.matches_criteria(&cv, &crit)? {
                        count += 1;
                    }
                }
                Ok(Value::Num(count as f64))
            }

            // ---- logical ----
            "IF" => {
                if self.t_truthy(&a[0])? {
                    self.t_value(&a[1])
                } else if a.len() > 2 {
                    self.t_value(&a[2])
                } else {
                    Ok(Value::Bool(false))
                }
            }
            "IFS" => {
                let mut i = 0;
                while i + 1 < a.len() {
                    if self.t_truthy(&a[i])? {
                        return self.t_value(&a[i + 1]);
                    }
                    i += 2;
                }
                Err(ferr("#N/A"))
            }
            "IFERROR" => match self.t_value(&a[0]) {
                Ok(Value::Err(_)) => self.t_value(&a[1]),
                Ok(r) => Ok(r),
                Err(_) => self.t_value(&a[1]),
            },
            "IFNA" => match self.t_value(&a[0]) {
                Ok(Value::Err(c)) if c == "#N/A" => self.t_value(&a[1]),
                Ok(r) => Ok(r),
                Err(e) if e.0 == "#N/A" => self.t_value(&a[1]),
                Err(e) => Err(e),
            },
            "AND" => {
                let vals = self.all_vals(a)?;
                let f: Vec<bool> = vals.iter().filter_map(bool_or_null).collect();
                Ok(Value::Bool(!f.is_empty() && f.iter().all(|b| *b)))
            }
            "OR" => {
                let vals = self.all_vals(a)?;
                let f: Vec<bool> = vals.iter().filter_map(bool_or_null).collect();
                Ok(Value::Bool(f.iter().any(|b| *b)))
            }
            "NOT" => Ok(Value::Bool(!self.t_truthy(&a[0])?)),
            "ISERROR" => Ok(Value::Bool(self.is_error(&a[0]).is_some())),
            "ISERR" => {
                let e = self.is_error(&a[0]);
                Ok(Value::Bool(e.is_some() && e.as_deref() != Some("#N/A")))
            }
            "ISNA" => Ok(Value::Bool(self.is_error(&a[0]).as_deref() == Some("#N/A"))),
            "ISNUMBER" => Ok(Value::Bool(matches!(self.safe_value(&a[0]), Value::Num(_)))),
            "ISTEXT" => Ok(Value::Bool(matches!(self.safe_value(&a[0]), Value::Str(_)))),
            "ISBLANK" => Ok(Value::Bool(self.safe_value(&a[0]) == Value::Blank)),
            "ISLOGICAL" => Ok(Value::Bool(matches!(self.safe_value(&a[0]), Value::Bool(_)))),
            "NA" => Err(ferr("#N/A")),

            // ---- lookup ----
            "CHOOSE" => {
                let idx = self.t_num(&a[0])? as i32;
                if idx < 1 || idx as usize >= a.len() {
                    Err(ferr("#VALUE!"))
                } else {
                    self.t_value(&a[idx as usize])
                }
            }
            "VLOOKUP" => self.lookup(a, false),
            "HLOOKUP" => self.lookup(a, true),
            "MATCH" => self.match_fn(a),
            "INDEX" => self.index_fn(a),

            // ---- text ----
            "LEN" => Ok(Value::Num(self.t_str(&a[0])?.chars().count() as f64)),
            "LEFT" => {
                let s = self.t_str(&a[0])?;
                let k = if a.len() > 1 { self.t_num(&a[1])? as i32 } else { 1 }.max(0) as usize;
                Ok(Value::Str(s.chars().take(k).collect()))
            }
            "RIGHT" => {
                let s = self.t_str(&a[0])?;
                let k = if a.len() > 1 { self.t_num(&a[1])? as i32 } else { 1 }.max(0) as usize;
                let chars: Vec<char> = s.chars().collect();
                let start = chars.len().saturating_sub(k);
                Ok(Value::Str(chars[start..].iter().collect()))
            }
            "MID" => {
                let s: Vec<char> = self.t_str(&a[0])?.chars().collect();
                let start = (self.t_num(&a[1])? as i32 - 1).max(0) as usize;
                let len = (self.t_num(&a[2])? as i32).max(0) as usize;
                if start >= s.len() {
                    Ok(Value::Str(String::new()))
                } else {
                    let end = s.len().min(start + len);
                    Ok(Value::Str(s[start..end].iter().collect()))
                }
            }
            "UPPER" => Ok(Value::Str(self.t_str(&a[0])?.to_uppercase())),
            "LOWER" => Ok(Value::Str(self.t_str(&a[0])?.to_lowercase())),
            "TRIM" => {
                let s = self.t_str(&a[0])?;
                Ok(Value::Str(s.split_whitespace().collect::<Vec<_>>().join(" ")))
            }
            "PROPER" => {
                let s = self.t_str(&a[0])?;
                let out: Vec<String> = s
                    .split(' ')
                    .map(|w| {
                        let mut ch = w.chars();
                        match ch.next() {
                            Some(f) => f.to_uppercase().collect::<String>() + ch.as_str(),
                            None => String::new(),
                        }
                    })
                    .collect();
                Ok(Value::Str(out.join(" ")))
            }
            "CONCATENATE" | "CONCAT" => {
                let vals = self.all_vals(a)?;
                let mut out = String::new();
                for v in vals {
                    out.push_str(&self.str_of(v)?);
                }
                Ok(Value::Str(out))
            }
            "REPT" => {
                let s = self.t_str(&a[0])?;
                let k = (self.t_num(&a[1])? as i32).max(0) as usize;
                Ok(Value::Str(s.repeat(k)))
            }
            "EXACT" => Ok(Value::Bool(self.t_str(&a[0])? == self.t_str(&a[1])?)),
            "FIND" => {
                let start = if a.len() > 2 { self.t_num(&a[2])? as i32 - 1 } else { 0 }.max(0) as usize;
                let needle = self.t_str(&a[0])?;
                let hay = self.t_str(&a[1])?;
                match index_of(&hay, &needle, start, false) {
                    Some(i) => Ok(Value::Num((i + 1) as f64)),
                    None => Err(ferr("#VALUE!")),
                }
            }
            "SEARCH" => {
                let start = if a.len() > 2 { self.t_num(&a[2])? as i32 - 1 } else { 0 }.max(0) as usize;
                let needle = self.t_str(&a[0])?;
                let hay = self.t_str(&a[1])?;
                match index_of(&hay, &needle, start, true) {
                    Some(i) => Ok(Value::Num((i + 1) as f64)),
                    None => Err(ferr("#VALUE!")),
                }
            }
            "SUBSTITUTE" => {
                let s = self.t_str(&a[0])?;
                let from = self.t_str(&a[1])?;
                let to = self.t_str(&a[2])?;
                Ok(Value::Str(if from.is_empty() { s } else { s.replace(&from, &to) }))
            }
            "REPLACE" => {
                let s: Vec<char> = self.t_str(&a[0])?.chars().collect();
                let start = ((self.t_num(&a[1])? as i32 - 1).max(0) as usize).min(s.len());
                let len = (self.t_num(&a[2])? as i32).max(0) as usize;
                let end = s.len().min(start + len);
                let repl = self.t_str(&a[3])?;
                let mut out: String = s[..start].iter().collect();
                out.push_str(&repl);
                out.extend(s[end..].iter());
                Ok(Value::Str(out))
            }
            "VALUE" => {
                let s = self.t_str(&a[0])?;
                parse_f64(&s).map(Value::Num).ok_or_else(|| ferr("#VALUE!"))
            }
            "TEXT" => {
                let v = self.t_num(&a[0])?;
                let f = self.t_str(&a[1])?;
                Ok(Value::Str(text_format(v, &f)))
            }

            _ => self.apply_function2(name, a),
        }
    }
}

// MIN/MAX with empty -> 0.0 (mirrors Kotlin minOrNull()?.let{...} ?: Num(0.0)).
fn pipe_min(ns: &[f64]) -> f64 {
    if ns.is_empty() {
        0.0
    } else {
        ns.iter().cloned().fold(f64::INFINITY, f64::min)
    }
}
fn pipe_max(ns: &[f64]) -> f64 {
    if ns.is_empty() {
        0.0
    } else {
        ns.iter().cloned().fold(f64::NEG_INFINITY, f64::max)
    }
}

fn bool_or_null(v: &Value) -> Option<bool> {
    match v {
        Value::Num(x) => Some(*x != 0.0),
        Value::Bool(b) => Some(*b),
        _ => None,
    }
}

/// UTF-16-agnostic char index-of (returns char index), optional case-insensitive.
fn index_of(hay: &str, needle: &str, start: usize, ignore_case: bool) -> Option<usize> {
    let h: Vec<char> = if ignore_case {
        hay.chars().map(|c| c.to_ascii_uppercase()).collect()
    } else {
        hay.chars().collect()
    };
    let n: Vec<char> = if ignore_case {
        needle.chars().map(|c| c.to_ascii_uppercase()).collect()
    } else {
        needle.chars().collect()
    };
    if n.is_empty() {
        return Some(start.min(h.len()));
    }
    if start > h.len() {
        return None;
    }
    let mut i = start;
    while i + n.len() <= h.len() {
        if h[i..i + n.len()] == n[..] {
            return Some(i);
        }
        i += 1;
    }
    None
}

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
                if list.is_empty() || p < 0.0 || p > 1.0 {
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

impl<'a> Evaluator<'a> {
    fn apply_function3(&mut self, name: &str, a: &[ArgThunk]) -> R<Value> {
        match name {
            // ---- extended date ----
            "DAYS" => Ok(Value::Num(self.t_num(&a[0])?.floor() - self.t_num(&a[1])?.floor())),
            "DATEVALUE" => Ok(Value::Num(self.t_num(&a[0])?.floor())),
            "EDATE" => {
                let dt = serial_to_datetime(self.t_num(&a[0])?);
                let k = self.t_num(&a[1])? as i64;
                let (y2, m2, d2) = add_months(dt.year, dt.month, dt.day, k);
                Ok(Value::Num(date_serial(y2, m2, d2)))
            }
            "EOMONTH" => {
                let dt = serial_to_datetime(self.t_num(&a[0])?);
                let k = self.t_num(&a[1])? as i64;
                let (y2, m2, _) = add_months(dt.year, dt.month, 1, k);
                let last = days_in_month(y2, m2);
                Ok(Value::Num(date_serial(y2, m2, last)))
            }
            "WEEKNUM" => Ok(Value::Num(weeknum(self.t_num(&a[0])?) as f64)),
            "DATEDIF" => {
                let n0 = self.t_num(&a[0])?;
                let n1 = self.t_num(&a[1])?;
                let unit = self.t_str(&a[2])?.to_uppercase();
                let c1 = serial_to_datetime(n0);
                let c2 = serial_to_datetime(n1);
                match unit.as_str() {
                    "D" => Ok(Value::Num(n1.floor() - n0.floor())),
                    "M" => Ok(Value::Num(
                        ((c2.year - c1.year) * 12 + (c2.month - c1.month)) as f64,
                    )),
                    "Y" => Ok(Value::Num((c2.year - c1.year) as f64)),
                    "MD" => {
                        let d1 = c1.day;
                        let d2 = c2.day;
                        let mut diff = d2 - d1;
                        if diff < 0 {
                            let (py, pm, _) = add_months(c2.year, c2.month, 1, -1);
                            let prev_last = days_in_month(py, pm);
                            diff = prev_last - d1 + d2;
                        }
                        Ok(Value::Num(diff as f64))
                    }
                    "YM" => {
                        let mut m = (c2.year - c1.year) * 12 + (c2.month - c1.month);
                        if c2.day < c1.day {
                            m -= 1;
                        }
                        Ok(Value::Num((((m % 12) + 12) % 12) as f64))
                    }
                    "YD" => {
                        let mut start = date_serial(c2.year, c1.month, c1.day);
                        if start > n1.floor() {
                            start = date_serial(c2.year - 1, c1.month, c1.day);
                        }
                        Ok(Value::Num(n1.floor() - start))
                    }
                    _ => Err(ferr("#NUM!")),
                }
            }
            "NETWORKDAYS" => {
                let s = self.t_num(&a[0])?.floor() as i64;
                let e = self.t_num(&a[1])?.floor() as i64;
                let lo = s.min(e);
                let hi = s.max(e);
                let mut cnt = 0;
                for d in lo..=hi {
                    let dow = serial_to_datetime(d as f64).dow;
                    if dow != 7 && dow != 1 {
                        cnt += 1;
                    }
                }
                Ok(Value::Num((if e < s { -cnt } else { cnt }) as f64))
            }
            "WORKDAY" => {
                let mut d = self.t_num(&a[0])?.floor() as i64;
                let mut remaining = self.t_num(&a[1])? as i64;
                let step: i64 = if remaining >= 0 { 1 } else { -1 };
                while remaining != 0 {
                    d += step;
                    let dow = serial_to_datetime(d as f64).dow;
                    if dow != 7 && dow != 1 {
                        remaining -= step;
                    }
                }
                Ok(Value::Num(d as f64))
            }

            // ---- financial ----
            "PMT" => {
                let r = self.t_num(&a[0])?;
                let nper = self.t_num(&a[1])?;
                let pv = self.t_num(&a[2])?;
                let fv = if a.len() > 3 { self.t_num(&a[3])? } else { 0.0 };
                Ok(Value::Num(if r == 0.0 {
                    -(pv + fv) / nper
                } else {
                    -(pv * (1.0 + r).powf(nper) + fv) * r / ((1.0 + r).powf(nper) - 1.0)
                }))
            }
            "FV" => {
                let r = self.t_num(&a[0])?;
                let nper = self.t_num(&a[1])?;
                let pmt = self.t_num(&a[2])?;
                let pv = if a.len() > 3 { self.t_num(&a[3])? } else { 0.0 };
                Ok(Value::Num(if r == 0.0 {
                    -(pv + pmt * nper)
                } else {
                    -(pv * (1.0 + r).powf(nper) + pmt * ((1.0 + r).powf(nper) - 1.0) / r)
                }))
            }
            "PV" => {
                let r = self.t_num(&a[0])?;
                let nper = self.t_num(&a[1])?;
                let pmt = self.t_num(&a[2])?;
                let fv = if a.len() > 3 { self.t_num(&a[3])? } else { 0.0 };
                Ok(Value::Num(if r == 0.0 {
                    -(fv + pmt * nper)
                } else {
                    -(fv + pmt * ((1.0 + r).powf(nper) - 1.0) / r) / (1.0 + r).powf(nper)
                }))
            }
            "NPV" => {
                let r = self.t_num(&a[0])?;
                let mut total = 0.0;
                let mut t = 1i32;
                for i in 1..a.len() {
                    for cf in self.t_nums(&a[i])? {
                        total += cf / (1.0 + r).powi(t);
                        t += 1;
                    }
                }
                Ok(Value::Num(total))
            }
            "NPER" => {
                let r = self.t_num(&a[0])?;
                let pmt = self.t_num(&a[1])?;
                let pv = self.t_num(&a[2])?;
                let fv = if a.len() > 3 { self.t_num(&a[3])? } else { 0.0 };
                Ok(Value::Num(if r == 0.0 {
                    -(pv + fv) / pmt
                } else {
                    ((pmt - fv * r) / (pmt + pv * r)).ln() / (1.0 + r).ln()
                }))
            }

            // ---- info / logical ----
            "ISEVEN" => Ok(Value::Bool(self.t_num(&a[0])?.trunc() as i64 % 2 == 0)),
            "ISODD" => Ok(Value::Bool(self.t_num(&a[0])?.trunc() as i64 % 2 != 0)),
            "ISFORMULA" => {
                let r = self.t_ref(&a[0]);
                let ok = match r {
                    Some(rf) => self
                        .cell_at_ref(&rf.sheet, rf.r1.min(rf.r2), rf.c1.min(rf.c2))
                        .map(|c| c.formula.is_some())
                        .unwrap_or(false),
                    None => false,
                };
                Ok(Value::Bool(ok))
            }
            "ISREF" => Ok(Value::Bool(self.t_ref(&a[0]).is_some())),
            "ISNONTEXT" => Ok(Value::Bool(!matches!(self.safe_value(&a[0]), Value::Str(_)))),
            "N" => match self.t_value(&a[0])? {
                Value::Num(x) => Ok(Value::Num(x)),
                Value::Bool(b) => Ok(Value::Num(if b { 1.0 } else { 0.0 })),
                Value::Err(c) => Ok(Value::Err(c)),
                _ => Ok(Value::Num(0.0)),
            },
            "TYPE" => Ok(Value::Num(match self.safe_value(&a[0]) {
                Value::Num(_) => 1.0,
                Value::Str(_) => 2.0,
                Value::Bool(_) => 4.0,
                Value::Err(_) => 16.0,
                Value::Blank => 1.0,
            })),
            "ERROR.TYPE" => {
                let code = self.is_error(&a[0]);
                let t = match code.as_deref() {
                    Some("#NULL!") => Some(1),
                    Some("#DIV/0!") => Some(2),
                    Some("#VALUE!") => Some(3),
                    Some("#REF!") => Some(4),
                    Some("#NAME?") => Some(5),
                    Some("#NUM!") => Some(6),
                    Some("#N/A") => Some(7),
                    _ => None,
                };
                match t {
                    Some(x) => Ok(Value::Num(x as f64)),
                    None => Err(ferr("#N/A")),
                }
            }
            "SHEET" => {
                let idx = self
                    .wb
                    .sheets
                    .iter()
                    .position(|s| s.name == self.sheet_name);
                Ok(Value::Num(match idx {
                    Some(i) => (i + 1) as f64,
                    None => 1.0,
                }))
            }
            "SHEETS" => Ok(Value::Num(if self.wb.sheets.is_empty() {
                1.0
            } else {
                self.wb.sheets.len() as f64
            })),

            // ---- math (phase 1) ----
            "QUOTIENT" => {
                let d = self.t_num(&a[1])?;
                if d == 0.0 {
                    Err(ferr("#DIV/0!"))
                } else {
                    Ok(Value::Num((self.t_num(&a[0])? / d).trunc()))
                }
            }
            "SEC" => Ok(Value::Num(1.0 / self.t_num(&a[0])?.cos())),
            "CSC" => Ok(Value::Num(1.0 / self.t_num(&a[0])?.sin())),
            "COT" => Ok(Value::Num(1.0 / self.t_num(&a[0])?.tan())),
            "SINH" => Ok(Value::Num(self.t_num(&a[0])?.sinh())),
            "COSH" => Ok(Value::Num(self.t_num(&a[0])?.cosh())),
            "TANH" => Ok(Value::Num(self.t_num(&a[0])?.tanh())),
            "ASINH" => Ok(Value::Num(self.t_num(&a[0])?.asinh())),
            "ACOSH" => Ok(Value::Num(self.t_num(&a[0])?.acosh())),
            "ATANH" => Ok(Value::Num(self.t_num(&a[0])?.atanh())),
            "MULTINOMIAL" => {
                let ns: Vec<i64> = self.all_nums(a)?.iter().map(|x| *x as i64).collect();
                let mut r = fact_d(ns.iter().sum());
                for x in ns {
                    r /= fact_d(x);
                }
                Ok(Value::Num(r))
            }
            "SUMX2PY2" => {
                let (xs, ys) = self.pair(a, 0, 1)?;
                let mut t = 0.0;
                for i in 0..xs.len() {
                    t += xs[i] * xs[i] + ys[i] * ys[i];
                }
                Ok(Value::Num(t))
            }
            "SUMX2MY2" => {
                let (xs, ys) = self.pair(a, 0, 1)?;
                let mut t = 0.0;
                for i in 0..xs.len() {
                    t += xs[i] * xs[i] - ys[i] * ys[i];
                }
                Ok(Value::Num(t))
            }
            "SUMXMY2" => {
                let (xs, ys) = self.pair(a, 0, 1)?;
                let mut t = 0.0;
                for i in 0..xs.len() {
                    t += (xs[i] - ys[i]).powi(2);
                }
                Ok(Value::Num(t))
            }
            "BASE" => {
                let num = self.t_num(&a[0])? as i64;
                let radix = self.t_num(&a[1])? as u32;
                let min_len = if a.len() > 2 { self.t_num(&a[2])? as usize } else { 0 };
                if radix < 2 || radix > 36 {
                    Err(ferr("#NUM!"))
                } else {
                    let s = to_radix(num, radix).to_uppercase();
                    Ok(Value::Str(pad_start(&s, min_len, '0')))
                }
            }
            "DECIMAL" => {
                let radix = self.t_num(&a[1])? as u32;
                if radix < 2 || radix > 36 {
                    Err(ferr("#NUM!"))
                } else {
                    let s = self.t_str(&a[0])?;
                    match i64::from_str_radix(s.trim(), radix) {
                        Ok(v) => Ok(Value::Num(v as f64)),
                        Err(_) => Err(ferr("#NUM!")),
                    }
                }
            }
            "ARABIC" => Ok(Value::Num(roman_to_arabic(&self.t_str(&a[0])?) as f64)),
            "ROMAN" => Ok(Value::Str(arabic_to_roman(self.t_num(&a[0])? as i32))),

            // ---- bitwise (phase 1) ----
            "BITAND" => Ok(Value::Num(
                (self.t_num(&a[0])? as i64 & self.t_num(&a[1])? as i64) as f64,
            )),
            "BITOR" => Ok(Value::Num(
                (self.t_num(&a[0])? as i64 | self.t_num(&a[1])? as i64) as f64,
            )),
            "BITXOR" => Ok(Value::Num(
                (self.t_num(&a[0])? as i64 ^ self.t_num(&a[1])? as i64) as f64,
            )),
            "BITLSHIFT" => {
                let x = self.t_num(&a[0])? as i64;
                let sh = self.t_num(&a[1])? as i64;
                Ok(Value::Num((if sh >= 0 { x << sh } else { x >> (-sh) }) as f64))
            }
            "BITRSHIFT" => {
                let x = self.t_num(&a[0])? as i64;
                let sh = self.t_num(&a[1])? as i64;
                Ok(Value::Num((if sh >= 0 { x >> sh } else { x << (-sh) }) as f64))
            }

            _ => self.apply_function4(name, a),
        }
    }
}

fn to_radix(mut num: i64, radix: u32) -> String {
    if num == 0 {
        return "0".to_string();
    }
    let neg = num < 0;
    if neg {
        num = -num;
    }
    let digits = b"0123456789abcdefghijklmnopqrstuvwxyz";
    let mut out = Vec::new();
    while num > 0 {
        out.push(digits[(num % radix as i64) as usize]);
        num /= radix as i64;
    }
    if neg {
        out.push(b'-');
    }
    out.reverse();
    String::from_utf8(out).unwrap()
}

fn pad_start(s: &str, min_len: usize, pad: char) -> String {
    let len = s.chars().count();
    if len >= min_len {
        s.to_string()
    } else {
        let mut out = String::new();
        for _ in 0..(min_len - len) {
            out.push(pad);
        }
        out.push_str(s);
        out
    }
}

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
                if list.is_empty() || q < 0 || q > 4 {
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
                    for t in 0..flows.len() {
                        npv += flows[t] / (1.0 + r).powi(t as i32);
                        if t > 0 {
                            d += -(t as f64) * flows[t] / (1.0 + r).powi(t as i32 + 1);
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
                for i in 0..lv.len() {
                    if self.compare_op("=", &lv[i], &key)? {
                        best = i as i32;
                        break;
                    }
                    let nv = self.cmp_num(&lv[i])?;
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

#[cfg(test)]
mod tests {
    use super::*;

    // Sheet1: A1..A3 = 1,2,3 ; B1..B3 = 10,20,30 ; C1 holds the formula under test
    // (column C never overlaps the A/B ranges the tests read).
    fn wb_with(formula: &str) -> Workbook {
        let data = [[1.0, 10.0], [2.0, 20.0], [3.0, 30.0]];
        let mut rows: Vec<String> = Vec::new();
        for (r, row) in data.iter().enumerate() {
            let mut cells: Vec<String> = row
                .iter()
                .map(|v| format!("{{\"text\":\"{}\",\"numberValue\":{}}}", v, v))
                .collect();
            if r == 0 {
                cells.push(format!(
                    "{{\"text\":\"\",\"formula\":\"{}\"}}",
                    formula.replace('"', "\\\"")
                ));
            }
            rows.push(format!("{{\"cells\":[{}]}}", cells.join(",")));
        }
        let json = format!(
            "{{\"sheets\":[{{\"name\":\"Sheet1\",\"rows\":[{}]}}]}}",
            rows.join(",")
        );
        Workbook::from_json(&json, 1_700_000_000_000).unwrap()
    }

    fn eval(formula: &str) -> String {
        let wb = wb_with(formula);
        wb.display_value(0, 0, 2) // C1
    }

    #[test]
    fn arithmetic_and_precedence() {
        assert_eq!(eval("of:=1+2*3"), "7");
        assert_eq!(eval("of:=(1+2)*3"), "9");
        assert_eq!(eval("of:=2^3"), "8");
        assert_eq!(eval("of:=10/4"), "2.5");
        assert_eq!(eval("of:=-2+5"), "3");
        assert_eq!(eval("of:=1+2*3-4/2"), "5");
    }

    #[test]
    fn logical_functions() {
        assert_eq!(eval("of:=IF(1>0;\"yes\";\"no\")"), "yes");
        assert_eq!(eval("of:=IF(1>2;\"yes\";\"no\")"), "no");
        assert_eq!(eval("of:=AND(1;1;1)"), "TRUE");
        assert_eq!(eval("of:=AND(1;0)"), "FALSE");
        assert_eq!(eval("of:=OR(0;0;1)"), "TRUE");
        assert_eq!(eval("of:=NOT(0)"), "TRUE");
    }

    #[test]
    fn ranges_sum_average() {
        assert_eq!(eval("of:=SUM([.A1:.A3])"), "6");
        assert_eq!(eval("of:=AVERAGE([.A1:.A3])"), "2");
        assert_eq!(eval("of:=MAX([.B1:.B3])"), "30");
        assert_eq!(eval("of:=MIN([.B1:.B3])"), "10");
        assert_eq!(eval("of:=COUNT([.A1:.B3])"), "6");
    }

    #[test]
    fn div_by_zero_propagates() {
        assert_eq!(eval("of:=1/0"), "#DIV/0!");
        assert_eq!(eval("of:=SUM([.A1:.A3])/0"), "#DIV/0!");
        assert_eq!(eval("of:=IFERROR(1/0;42)"), "42");
    }

    #[test]
    fn string_functions() {
        assert_eq!(eval("of:=UPPER(\"abc\")"), "ABC");
        assert_eq!(eval("of:=LEFT(\"hello\";3)"), "hel");
        assert_eq!(eval("of:=MID(\"hello\";2;3)"), "ell");
        assert_eq!(eval("of:=LEN(\"hello\")"), "5");
        assert_eq!(eval("of:=CONCATENATE(\"a\";\"b\";\"c\")"), "abc");
        assert_eq!(eval("of:=\"foo\"&\"bar\""), "foobar");
    }

    #[test]
    fn vlookup_and_index_match() {
        // A column keys 1,2,3 -> B column 10,20,30.
        assert_eq!(eval("of:=VLOOKUP(2;[.A1:.B3];2;0)"), "20");
        assert_eq!(eval("of:=INDEX([.B1:.B3];2;1)"), "20");
        assert_eq!(eval("of:=MATCH(3;[.A1:.A3];0)"), "3");
        assert_eq!(eval("of:=INDEX([.B1:.B3];MATCH(3;[.A1:.A3];0);1)"), "30");
    }

    #[test]
    fn date_functions() {
        assert_eq!(eval("of:=DATE(2020;1;1)"), "43831");
        assert_eq!(eval("of:=YEAR(43831)"), "2020");
        assert_eq!(eval("of:=MONTH(43831)"), "1");
        assert_eq!(eval("of:=DAY(43831)"), "1");
        // 2020-01-01 was a Wednesday -> Java DAY_OF_WEEK = 4.
        assert_eq!(eval("of:=WEEKDAY(43831)"), "4");
        assert_eq!(eval("of:=DATE(2020;2;29)"), "43890");
    }

    #[test]
    fn conditional_aggregation() {
        assert_eq!(eval("of:=SUMIF([.A1:.A3];\">1\")"), "5");
        assert_eq!(eval("of:=COUNTIF([.A1:.A3];\">=2\")"), "2");
        assert_eq!(eval("of:=SUMIF([.A1:.A3];\">1\";[.B1:.B3])"), "50");
    }

    #[test]
    fn number_formatting() {
        assert_eq!(format_number(6.0), "6");
        assert_eq!(format_number(2.5), "2.5");
        assert_eq!(format_number(1.0 / 3.0), "0.3333");
        assert_eq!(format_fixed(1234.5, 2, true), "1,234.50");
        assert_eq!(format_fixed(1234.567, 2, false), "1234.57");
    }

    #[test]
    fn cycle_detection() {
        // C1 refers to itself -> #REF!.
        let wb = wb_with("of:=[.C1]+1");
        assert_eq!(wb.display_value(0, 0, 2), "#REF!");
    }

    #[test]
    fn error_codes() {
        assert_eq!(eval("of:=NOTAREALFUNC()"), "#NAME?");
        assert_eq!(eval("of:=NA()"), "#N/A");
        assert_eq!(eval("of:=SQRT(-1)"), "#ERR"); // NaN -> #ERR
    }

    #[test]
    fn format_value_json_standalone() {
        // No format ("null"/empty) -> plain number formatting.
        assert_eq!(format_value_json(6.0, "null"), "6");
        assert_eq!(format_value_json(2.5, ""), "2.5");
        // Percent + decimals honored from the serialized NumberFormat JSON.
        assert_eq!(
            format_value_json(0.5, "{\"decimals\":1,\"percent\":true}"),
            "50.0%"
        );
        // Grouping + currency.
        assert_eq!(
            format_value_json(1234.5, "{\"decimals\":2,\"grouping\":true,\"currencySymbol\":\"$\"}"),
            "$1,234.50"
        );
        // Unparseable JSON falls back to no-format plain number.
        assert_eq!(format_value_json(3.0, "not json"), "3");
    }
}
