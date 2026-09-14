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
