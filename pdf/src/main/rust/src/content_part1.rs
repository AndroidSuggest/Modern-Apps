impl<'a> Lexer<'a> {
    #[inline]
    fn peek(&self) -> Option<u8> {
        self.d.get(self.p).copied()
    }

    /// Skip white space and `%` comments (§7.2.4).
    fn skip_ws(&mut self) {
        while let Some(b) = self.peek() {
            if is_ws(b) {
                self.p += 1;
            } else if b == b'%' {
                while let Some(c) = self.peek() {
                    if c == b'\n' || c == b'\r' {
                        break;
                    }
                    self.p += 1;
                }
            } else {
                break;
            }
        }
    }

    fn run(mut self) -> Vec<Operation> {
        let mut ops: Vec<Operation> = Vec::new();
        let mut stack: Vec<Object> = Vec::new();
        while self.p < self.d.len() && ops.len() < MAX_OPERATIONS {
            self.skip_ws();
            let Some(b) = self.peek() else { break };
            match b {
                // Operand starts.
                b'/' | b'(' | b'[' | b'<' => {
                    let before = self.p;
                    match self.object(0) {
                        Some(o) => {
                            if stack.len() < MAX_OPERANDS {
                                stack.push(o);
                            }
                        }
                        // Unparseable operand: step over one byte so we always make
                        // progress, then keep going.
                        None => self.p = (before + 1).max(self.p),
                    }
                }
                b'+' | b'-' | b'.' | b'0'..=b'9' => {
                    let o = self.number();
                    if stack.len() < MAX_OPERANDS {
                        stack.push(o);
                    }
                }
                // Stray closers and PostScript braces: not valid here, drop them.
                b']' | b'}' | b'{' | b')' => self.p += 1,
                b'>' => self.p += 1,
                _ => {
                    let tok = self.keyword();
                    if tok.is_empty() {
                        // Not a regular character (already handled above): skip it.
                        self.p += 1;
                        continue;
                    }
                    match tok.as_slice() {
                        b"true" => {
                            if stack.len() < MAX_OPERANDS {
                                stack.push(Object::Boolean(true));
                            }
                        }
                        b"false" => {
                            if stack.len() < MAX_OPERANDS {
                                stack.push(Object::Boolean(false));
                            }
                        }
                        b"null" => {
                            if stack.len() < MAX_OPERANDS {
                                stack.push(Object::Null);
                            }
                        }
                        b"BI" => {
                            // Pending operands cannot belong to BI; discard them.
                            stack.clear();
                            match self.inline_image() {
                                InlineResult::Image(stream) => ops.push(Operation {
                                    operator: "BI".to_string(),
                                    operands: vec![Object::Stream(stream)],
                                }),
                                // Lost sync inside binary data. Everything before the
                                // image is kept; going further would resynchronize into
                                // pixel data and emit garbage operators.
                                InlineResult::Abort => break,
                            }
                        }
                        // `ID`/`EI` outside a BI mean we are out of step; ignore them
                        // rather than treating them as drawing operators.
                        b"ID" | b"EI" => stack.clear(),
                        _ => {
                            let operands = std::mem::take(&mut stack);
                            ops.push(Operation {
                                operator: String::from_utf8_lossy(&tok).into_owned(),
                                operands,
                            });
                        }
                    }
                }
            }
        }
        ops
    }

    /// Read a run of regular characters (a number, keyword or operator token).
    fn keyword(&mut self) -> Vec<u8> {
        let s = self.p;
        while let Some(b) = self.peek() {
            if !is_regular(b) {
                break;
            }
            self.p += 1;
            if self.p - s >= MAX_NAME {
                break;
            }
        }
        self.d[s..self.p].to_vec()
    }

    /// Parse one operand. `depth` bounds array/dictionary nesting.
    fn object(&mut self, depth: u32) -> Option<Object> {
        self.skip_ws();
        let b = self.peek()?;
        match b {
            b'/' => Some(Object::Name(self.name())),
            b'(' => Some(Object::String(self.literal_string(), StringFormat::Literal)),
            b'[' => {
                if depth >= MAX_DEPTH {
                    return None;
                }
                Some(Object::Array(self.array(depth + 1)))
            }
            b'<' => {
                if self.d.get(self.p + 1) == Some(&b'<') {
                    if depth >= MAX_DEPTH {
                        return None;
                    }
                    Some(Object::Dictionary(self.dictionary(depth + 1)))
                } else {
                    Some(Object::String(self.hex_string(), StringFormat::Hexadecimal))
                }
            }
            b'+' | b'-' | b'.' | b'0'..=b'9' => Some(self.number()),
            _ if is_regular(b) => match self.keyword().as_slice() {
                b"true" => Some(Object::Boolean(true)),
                b"false" => Some(Object::Boolean(false)),
                b"null" => Some(Object::Null),
                // A bare keyword where a value was expected (e.g. an operator, or `R`
                // from a reference that cannot exist in a content stream).
                _ => None,
            },
            _ => None,
        }
    }

    /// §7.3.5 name object, including `#XX` hex escapes.
    fn name(&mut self) -> Vec<u8> {
        self.p += 1; // the '/'
        let mut out = Vec::new();
        while let Some(b) = self.peek() {
            if !is_regular(b) {
                break;
            }
            self.p += 1;
            if b == b'#' {
                let hi = self.peek().and_then(|c| (c as char).to_digit(16));
                let lo = self
                    .d
                    .get(self.p + 1)
                    .and_then(|c| (*c as char).to_digit(16));
                if let (Some(hi), Some(lo)) = (hi, lo) {
                    out.push(((hi << 4) | lo) as u8);
                    self.p += 2;
                    continue;
                }
                // Malformed escape: keep the '#' literally, as viewers do.
            }
            out.push(b);
            if out.len() >= MAX_NAME {
                break;
            }
        }
        out
    }

    /// §7.3.4.2 literal string: balanced parentheses plus backslash escapes.
    fn literal_string(&mut self) -> Vec<u8> {
        self.p += 1; // the '('
        let mut out = Vec::new();
        let mut depth = 1u32;
        while let Some(b) = self.peek() {
            self.p += 1;
            match b {
                b'\\' => {
                    let Some(e) = self.peek() else { break };
                    self.p += 1;
                    match e {
                        b'n' => out.push(b'\n'),
                        b'r' => out.push(b'\r'),
                        b't' => out.push(b'\t'),
                        b'b' => out.push(8),
                        b'f' => out.push(12),
                        b'(' => out.push(b'('),
                        b')' => out.push(b')'),
                        b'\\' => out.push(b'\\'),
                        // A backslash before an EOL is a line continuation: both the
                        // backslash and the EOL are dropped.
                        b'\r' => {
                            if self.peek() == Some(b'\n') {
                                self.p += 1;
                            }
                        }
                        b'\n' => {}
                        // \ddd octal, one to three digits.
                        b'0'..=b'7' => {
                            let mut v = (e - b'0') as u32;
                            for _ in 0..2 {
                                match self.peek() {
                                    Some(c @ b'0'..=b'7') => {
                                        v = v * 8 + (c - b'0') as u32;
                                        self.p += 1;
                                    }
                                    _ => break,
                                }
                            }
                            out.push((v & 0xFF) as u8);
                        }
                        // §7.3.4.2: a backslash before any other character is ignored
                        // and the character stands for itself.
                        other => out.push(other),
                    }
                }
                b'(' => {
                    depth += 1;
                    out.push(b'(');
                }
                b')' => {
                    depth -= 1;
                    if depth == 0 {
                        break;
                    }
                    out.push(b')');
                }
                // §7.3.4.2: an end-of-line inside a literal string means LF, whichever
                // of CR / LF / CRLF was actually written.
                b'\r' => {
                    if self.peek() == Some(b'\n') {
                        self.p += 1;
                    }
                    out.push(b'\n');
                }
                other => out.push(other),
            }
            if out.len() >= MAX_STRING {
                break;
            }
        }
        out
    }

    /// §7.3.4.3 hexadecimal string. A trailing odd digit is padded with `0`.
    fn hex_string(&mut self) -> Vec<u8> {
        self.p += 1; // the '<'
        let mut digits: Vec<u8> = Vec::new();
        while let Some(b) = self.peek() {
            self.p += 1;
            if b == b'>' {
                break;
            }
            if b.is_ascii_hexdigit() {
                digits.push(b);
            }
            if digits.len() >= MAX_STRING {
                break;
            }
        }
        if digits.len() % 2 == 1 {
            digits.push(b'0');
        }
        digits
            .chunks(2)
            .map(|c| {
                let hi = (c[0] as char).to_digit(16).unwrap_or(0) as u8;
                let lo = (c[1] as char).to_digit(16).unwrap_or(0) as u8;
                (hi << 4) | lo
            })
            .collect()
    }

    /// §7.3.6 array.
    fn array(&mut self, depth: u32) -> Vec<Object> {
        self.p += 1; // the '['
        let mut out = Vec::new();
        loop {
            self.skip_ws();
            match self.peek() {
                None => break,
                Some(b']') => {
                    self.p += 1;
                    break;
                }
                Some(_) => {
                    let before = self.p;
                    match self.object(depth) {
                        Some(o) => {
                            if out.len() < MAX_ARRAY_ITEMS {
                                out.push(o);
                            }
                        }
                        None => {
                            // Skip the offending token so the array still terminates.
                            if self.p == before {
                                self.p += 1;
                            }
                        }
                    }
                }
            }
        }
        out
    }

    /// §7.3.7 dictionary.
    fn dictionary(&mut self, depth: u32) -> Dictionary {
        self.p += 2; // the '<<'
        let mut dict = Dictionary::new();
        loop {
            self.skip_ws();
            match self.peek() {
                None => break,
                Some(b'>') => {
                    // Consume '>>' (or a lone '>' from a malformed stream).
                    self.p += 1;
                    if self.peek() == Some(b'>') {
                        self.p += 1;
                    }
                    break;
                }
                Some(b'/') => {
                    let key = self.name();
                    let before = self.p;
                    match self.object(depth) {
                        Some(v) => {
                            if dict.len() < MAX_DICT_ENTRIES {
                                dict.set(key, v);
                            }
                        }
                        None => {
                            if self.p == before {
                                self.p += 1;
                            }
                        }
                    }
                }
                // A non-name where a key belongs: drop it and resynchronize.
                Some(_) => {
                    let before = self.p;
                    if self.object(depth).is_none() && self.p == before {
                        self.p += 1;
                    }
                }
            }
        }
        dict
    }

    /// §7.3.2 / §7.3.3 numeric object, accepting the malformed forms real files
    /// contain: `.5`, `4.`, `+3`, `--5`, and stray extra decimal points.
    fn number(&mut self) -> Object {
        let tok = self.keyword();
        Object::from(parse_number(&tok))
    }

    // -- Inline images (§8.9.7) --------------------------------------------

    /// Parse `BI <dict> ID <binary> EI`, with `BI` already consumed.
    fn inline_image(&mut self) -> InlineResult {
        // 1. Key/value pairs up to the `ID` keyword. They are NOT wrapped in `<< >>`.
        let mut dict = Dictionary::new();
        loop {
            self.skip_ws();
            let Some(b) = self.peek() else {
                return InlineResult::Abort;
            };
            if b == b'/' {
                let key = self.name();
                let before = self.p;
                match self.object(0) {
                    Some(v) => {
                        if dict.len() < MAX_DICT_ENTRIES {
                            dict.set(key, v);
                        }
                    }
                    None => {
                        if self.p == before {
                            self.p += 1;
                        }
                    }
                }
                continue;
            }
            if is_regular(b) {
                let before = self.p;
                let tok = self.keyword();
                match tok.as_slice() {
                    b"ID" => break,
                    // `EI` with no `ID`: a degenerate but harmless empty image.
                    b"EI" => return InlineResult::Image(Stream::new(dict, Vec::new())),
                    b"true" | b"false" | b"null" => continue,
                    _ if tok.is_empty() => {
                        self.p = before + 1;
                        continue;
                    }
                    // Stray token in the dictionary: ignore and carry on.
                    _ => continue,
                }
            }
            // Any other byte (a stray delimiter): step over it.
            self.p += 1;
        }

        // 2. §8.9.7: exactly ONE whitespace byte separates `ID` from the data.
        if self.peek().is_some_and(is_ws) {
            self.p += 1;
        }
        let data_start = self.p;
        let remaining = self.d.len() - data_start;

        // 3. Prefer a COMPUTED length over scanning for `EI`.
        //
        // `extra` covers producers that write CRLF after `ID` even though the spec
        // allows a single byte: the second candidate treats the LF as a separator
        // rather than as the first pixel byte. Whichever candidate lands on a
        // verified `EI` wins, so this cannot silently shift the data.
        for len in inline_len_candidates(&dict, remaining) {
            for extra in [0usize, 1usize] {
                if extra == 1 && !self.d.get(data_start).copied().is_some_and(is_ws) {
                    continue;
                }
                let s = data_start + extra;
                let Some(end) = s.checked_add(len) else { continue };
                if end > self.d.len() {
                    continue;
                }
                if let Some(after) = ei_at(self.d, end) {
                    let data = self.d[s..end].to_vec();