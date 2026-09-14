fn parse_ps_op(s: &str) -> Option<PsOp> {
    use PsOp::*;
    Some(match s {
        "abs" => Abs, "add" => Add, "atan" => Atan, "ceiling" => Ceiling,
        "cos" => Cos, "cvi" => Cvi, "cvr" => Cvr, "div" => Div, "exp" => Exp,
        "floor" => Floor, "idiv" => Idiv, "ln" => Ln, "log" => Log, "mod" => Mod,
        "mul" => Mul, "neg" => Neg, "round" => Round, "sin" => Sin, "sqrt" => Sqrt,
        "sub" => Sub, "truncate" => Truncate,
        "and" => And, "bitshift" => Bitshift, "eq" => Eq, "false" => False,
        "ge" => Ge, "gt" => Gt, "le" => Le, "lt" => Lt, "ne" => Ne, "not" => Not,
        "or" => Or, "true" => True, "xor" => Xor,
        "if" => If, "ifelse" => Ifelse,
        "copy" => Copy, "dup" => Dup, "exch" => Exch, "index" => Index,
        "pop" => Pop, "roll" => Roll,
        _ => return None,
    })
}

#[derive(Clone)]
enum PsVal {
    Num(f64),
    Proc(Vec<PsToken>),
}

fn eval_ps(program: &[PsToken], inputs: &[f64]) -> Option<Vec<f64>> {
    let mut stack: Vec<PsVal> = inputs.iter().map(|v| PsVal::Num(*v)).collect();
    let mut steps = 0usize;
    exec_ps(program, &mut stack, &mut steps, 0)?;
    Some(stack.into_iter().filter_map(|v| match v {
        PsVal::Num(n) => Some(n),
        PsVal::Proc(_) => None,
    }).collect())
}

fn exec_ps(tokens: &[PsToken], stack: &mut Vec<PsVal>, steps: &mut usize, depth: u32) -> Option<()> {
    if depth > MAX_PS_DEPTH {
        return None;
    }
    for tok in tokens {
        *steps += 1;
        if *steps > MAX_PS_STEPS || stack.len() > MAX_PS_STACK {
            return None;
        }
        match tok {
            PsToken::Num(n) => stack.push(PsVal::Num(*n)),
            PsToken::Proc(p) => stack.push(PsVal::Proc(p.clone())),
            PsToken::Op(op) => exec_ps_op(*op, stack, steps, depth)?,
        }
    }
    Some(())
}

fn pop_num(stack: &mut Vec<PsVal>) -> Option<f64> {
    match stack.pop()? {
        PsVal::Num(n) => Some(n),
        PsVal::Proc(_) => None,
    }
}

fn pop_proc(stack: &mut Vec<PsVal>) -> Option<Vec<PsToken>> {
    match stack.pop()? {
        PsVal::Proc(p) => Some(p),
        PsVal::Num(_) => None,
    }
}

fn exec_ps_op(op: PsOp, stack: &mut Vec<PsVal>, steps: &mut usize, depth: u32) -> Option<()> {
    use PsOp::*;
    let bool_of = |b: bool| PsVal::Num(if b { 1.0 } else { 0.0 });
    match op {
        Abs => { let a = pop_num(stack)?; stack.push(PsVal::Num(a.abs())); }
        Neg => { let a = pop_num(stack)?; stack.push(PsVal::Num(-a)); }
        Sqrt => { let a = pop_num(stack)?; stack.push(PsVal::Num(a.max(0.0).sqrt())); }
        Sin => { let a = pop_num(stack)?; stack.push(PsVal::Num(a.to_radians().sin())); }
        Cos => { let a = pop_num(stack)?; stack.push(PsVal::Num(a.to_radians().cos())); }
        Ln => { let a = pop_num(stack)?; stack.push(PsVal::Num(if a > 0.0 { a.ln() } else { 0.0 })); }
        Log => { let a = pop_num(stack)?; stack.push(PsVal::Num(if a > 0.0 { a.log10() } else { 0.0 })); }
        Floor => { let a = pop_num(stack)?; stack.push(PsVal::Num(a.floor())); }
        Ceiling => { let a = pop_num(stack)?; stack.push(PsVal::Num(a.ceil())); }
        Round => {
            // PLRM `round` returns the nearest integer and, for a value exactly
            // halfway, the GREATER of the two. `f64::round` breaks that tie away
            // from zero instead, so -1.5 came out -2 where PostScript gives -1.
            let a = pop_num(stack)?;
            stack.push(PsVal::Num((a + 0.5).floor()));
        }
        Truncate => { let a = pop_num(stack)?; stack.push(PsVal::Num(a.trunc())); }
        Cvi => { let a = pop_num(stack)?; stack.push(PsVal::Num(a.trunc())); }
        Cvr => { /* no-op: already real */ }
        Not => {
            let a = pop_num(stack)?;
            // If used as boolean it's 0/1; as bitwise on ints, invert.
            if a == 0.0 || a == 1.0 {
                stack.push(bool_of(a == 0.0));
            } else {
                stack.push(PsVal::Num(!(a as i64) as f64));
            }
        }
        Add => { let b = pop_num(stack)?; let a = pop_num(stack)?; stack.push(PsVal::Num(a + b)); }
        Sub => { let b = pop_num(stack)?; let a = pop_num(stack)?; stack.push(PsVal::Num(a - b)); }
        Mul => { let b = pop_num(stack)?; let a = pop_num(stack)?; stack.push(PsVal::Num(a * b)); }
        Div => {
            let b = pop_num(stack)?; let a = pop_num(stack)?;
            stack.push(PsVal::Num(if b != 0.0 { a / b } else { 0.0 }));
        }
        Idiv => {
            // `f64 as i64` SATURATES, so any large real reaches i64::MIN, and
            // `i64::MIN / -1` overflows — which Rust panics on in every profile,
            // not just debug. The panic unwinds out of the whole page render.
            let b = pop_num(stack)? as i64; let a = pop_num(stack)? as i64;
            stack.push(PsVal::Num(if b != 0 { a.wrapping_div(b) as f64 } else { 0.0 }));
        }
        Mod => {
            // `i64::MIN % -1` overflows for the same reason as `idiv` above.
            let b = pop_num(stack)? as i64; let a = pop_num(stack)? as i64;
            stack.push(PsVal::Num(if b != 0 { a.wrapping_rem(b) as f64 } else { 0.0 }));
        }
        Exp => {
            let b = pop_num(stack)?; let a = pop_num(stack)?;
            // A negative base with a fractional exponent is NaN, which then survives
            // the /Range clip and every colour conversion below it. Same guard the
            // Type 2 `t^N` path already applies, for the same reason.
            let p = a.powf(b);
            stack.push(PsVal::Num(if p.is_finite() { p } else { 0.0 }));
        }
        Atan => {
            let den = pop_num(stack)?; let num_ = pop_num(stack)?;
            let mut deg = num_.atan2(den).to_degrees();
            if deg < 0.0 { deg += 360.0; }
            stack.push(PsVal::Num(deg));
        }
        And => {
            let b = pop_num(stack)?; let a = pop_num(stack)?;
            stack.push(PsVal::Num(((a as i64) & (b as i64)) as f64));
        }
        Or => {
            let b = pop_num(stack)?; let a = pop_num(stack)?;
            stack.push(PsVal::Num(((a as i64) | (b as i64)) as f64));
        }
        Xor => {
            let b = pop_num(stack)?; let a = pop_num(stack)?;
            stack.push(PsVal::Num(((a as i64) ^ (b as i64)) as f64));
        }
        Bitshift => {
            let shift = pop_num(stack)? as i64; let a = pop_num(stack)? as i64;
            // `-i64::MIN` overflows; a saturating negation cannot, and everything
            // past 63 clamps to a full-width shift anyway.
            let r = if shift >= 0 { a << (shift.min(63)) } else { a >> (shift.saturating_neg().min(63)) };
            stack.push(PsVal::Num(r as f64));
        }
        Eq => { let b = pop_num(stack)?; let a = pop_num(stack)?; stack.push(bool_of(a == b)); }
        Ne => { let b = pop_num(stack)?; let a = pop_num(stack)?; stack.push(bool_of(a != b)); }
        Gt => { let b = pop_num(stack)?; let a = pop_num(stack)?; stack.push(bool_of(a > b)); }
        Ge => { let b = pop_num(stack)?; let a = pop_num(stack)?; stack.push(bool_of(a >= b)); }
        Lt => { let b = pop_num(stack)?; let a = pop_num(stack)?; stack.push(bool_of(a < b)); }
        Le => { let b = pop_num(stack)?; let a = pop_num(stack)?; stack.push(bool_of(a <= b)); }
        True => stack.push(PsVal::Num(1.0)),
        False => stack.push(PsVal::Num(0.0)),
        Pop => { stack.pop()?; }
        Dup => { let v = stack.last()?.clone(); stack.push(v); }
        Exch => {
            let b = stack.pop()?; let a = stack.pop()?;
            stack.push(b); stack.push(a);
        }
        Copy => {
            let n = pop_num(stack)? as i64;
            if n > 0 {
                let n = n as usize;
                if n > stack.len() { return None; }
                let start = stack.len() - n;
                let slice: Vec<PsVal> = stack[start..].to_vec();
                for v in slice { stack.push(v); }
            }
        }
        Index => {
            let n = pop_num(stack)? as i64;
            if n < 0 { return None; }
            let n = n as usize;
            if n >= stack.len() { return None; }
            let v = stack[stack.len() - 1 - n].clone();
            stack.push(v);
        }
        Roll => {
            let j = pop_num(stack)? as i64;
            let n = pop_num(stack)? as i64;
            if n <= 0 { return Some(()); }
            let n = n as usize;
            if n > stack.len() { return None; }
            let start = stack.len() - n;
            let slice = &mut stack[start..];
            let jm = ((j % n as i64) + n as i64) % n as i64;
            slice.rotate_right(jm as usize);
        }
        If => {
            let proc = pop_proc(stack)?;
            let cond = pop_num(stack)?;
            if cond != 0.0 {
                exec_ps(&proc, stack, steps, depth + 1)?;
            }
        }
        Ifelse => {
            let proc2 = pop_proc(stack)?;
            let proc1 = pop_proc(stack)?;
            let cond = pop_num(stack)?;
            if cond != 0.0 {
                exec_ps(&proc1, stack, steps, depth + 1)?;
            } else {
                exec_ps(&proc2, stack, steps, depth + 1)?;
            }
        }
    }
    Some(())
}

/// Read a transfer function entry (`/TR` in an ExtGState soft-mask dictionary,
/// §11.6.5.2) as a 256-entry lookup table, or `None` when it has no effect.
///
/// §11.6.5.2 requires the mask value to pass THROUGH `/TR` before it is used as the
/// alpha. Ignoring it is not merely imprecise: an inverting `/TR` (`{ 1 exch sub }`, or
/// a Type 2 with `/C0 [1] /C1 [0]`) is the standard idiom for "mask out where the group
/// is bright", so with one present we hide exactly the wrong half of the content.
///
/// `None` is returned for `/Identity`, for an unparseable function, and for any function
/// whose sampled table is within one 8-bit step of the identity everywhere — the
/// overwhelmingly common case, which callers should not pay to carry or apply.
pub(crate) fn read_transfer_lut(doc: &Document, obj: &Object) -> Option<[u8; 256]> {
    if let Some(Object::Name(n)) = deref(doc, obj) {
        if n.as_slice() == b"Identity" || n.as_slice() == b"Default" {
            return None;
        }
    }
    let lut = PdfFunction::parse(doc, obj)?.to_lut256();
    if lut.iter().enumerate().all(|(i, v)| v.abs_diff(i as u8) <= 1) {
        return None;
    }
    Some(lut)
}
