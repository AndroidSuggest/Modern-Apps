use std::{fmt, str::FromStr};

use crate::errors::*;

/// A primitive java type. These are the things that can be represented without
/// an object.
#[allow(missing_docs)]
#[derive(Eq, PartialEq, Debug, Clone, Copy)]
pub enum Primitive {
    Boolean, // Z
    Byte,    // B
    Char,    // C
    Double,  // D
    Float,   // F
    Int,     // I
    Long,    // J
    Short,   // S
    Void,    // V
}

impl fmt::Display for Primitive {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match *self {
            Primitive::Boolean => write!(f, "Z"),
            Primitive::Byte => write!(f, "B"),
            Primitive::Char => write!(f, "C"),
            Primitive::Double => write!(f, "D"),
            Primitive::Float => write!(f, "F"),
            Primitive::Int => write!(f, "I"),
            Primitive::Long => write!(f, "J"),
            Primitive::Short => write!(f, "S"),
            Primitive::Void => write!(f, "V"),
        }
    }
}

/// Enum representing any java type in addition to method signatures.
#[allow(missing_docs)]
#[derive(Eq, PartialEq, Debug, Clone)]
pub enum JavaType {
    Primitive(Primitive),
    Object(String),
    Array(Box<JavaType>),
    Method(Box<TypeSignature>),
}

impl FromStr for JavaType {
    type Err = Error;

    fn from_str(s: &str) -> std::result::Result<Self, Self::Err> {
        SigParser::new(s)
            .parse_type()
            .map_err(|_| Error::ParseFailed(s.to_owned()))
    }
}

impl fmt::Display for JavaType {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match *self {
            JavaType::Primitive(ref ty) => ty.fmt(f),
            JavaType::Object(ref name) => write!(f, "L{name};"),
            JavaType::Array(ref ty) => write!(f, "[{ty}"),
            JavaType::Method(ref m) => m.fmt(f),
        }
    }
}

/// Enum representing any java type that may be used as a return value
///
/// This type intentionally avoids capturing any heap allocated types (to avoid
/// allocations while making JNI method calls) and so it doesn't fully qualify
/// the object or array types with a String like `JavaType::Object` does.
#[allow(missing_docs)]
#[derive(Eq, PartialEq, Debug, Clone)]
pub enum ReturnType {
    Primitive(Primitive),
    Object,
    Array,
}

impl FromStr for ReturnType {
    type Err = Error;

    fn from_str(s: &str) -> std::result::Result<Self, Self::Err> {
        SigParser::new(s)
            .parse_return()
            .map_err(|_| Error::ParseFailed(s.to_owned()))
    }
}

impl fmt::Display for ReturnType {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match *self {
            ReturnType::Primitive(ref ty) => ty.fmt(f),
            ReturnType::Object => write!(f, "L;"),
            ReturnType::Array => write!(f, "["),
        }
    }
}

/// A method type signature. This is the structure representation of something
/// like `(Ljava/lang/String;)Z`. Used by the `call_(object|static)_method`
/// functions on jnienv to ensure safety.
#[allow(missing_docs)]
#[derive(Eq, PartialEq, Debug, Clone)]
pub struct TypeSignature {
    pub args: Vec<JavaType>,
    pub ret: ReturnType,
}

impl TypeSignature {
    /// Parse a signature string into a TypeSignature enum.
    // Clippy suggests implementing `FromStr` or renaming it which is not possible in our case.
    #[allow(clippy::should_implement_trait)]
    pub fn from_str<S: AsRef<str>>(s: S) -> Result<TypeSignature> {
        let s = s.as_ref();
        match SigParser::new(s).parse_sig() {
            Ok(JavaType::Method(sig)) => Ok(*sig),
            _ => Err(Error::ParseFailed(s.to_owned())),
        }
    }
}

impl fmt::Display for TypeSignature {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "(")?;
        for a in &self.args {
            write!(f, "{a}")?;
        }
        write!(f, ")")?;
        write!(f, "{}", self.ret)?;
        Ok(())
    }
}

/// A tiny hand-written recursive-descent parser for JNI type signatures,
/// replacing the previous `combine` dependency (which also pulled in `bytes`).
///
/// Grammar (identical to the old `combine` parser, including its behaviour of
/// **not** requiring end-of-input — trailing characters after a complete type
/// are ignored, matching `combine::Parser::parse` returning the remainder):
///
/// ```text
/// primitive := 'Z'|'B'|'C'|'D'|'F'|'I'|'J'|'S'|'V'
/// object    := 'L' <one-or-more chars != ';'> ';'
/// array     := '[' type
/// type      := array | object | sig | primitive
/// return    := array | object | primitive          (array/object collapse to Array/Object)
/// args      := '(' type* ')'
/// sig       := args return
/// ```
struct SigParser<'a> {
    it: std::iter::Peekable<std::str::Chars<'a>>,
}

impl<'a> SigParser<'a> {
    fn new(s: &'a str) -> Self {
        Self {
            it: s.chars().peekable(),
        }
    }

    fn peek(&mut self) -> Option<char> {
        self.it.peek().copied()
    }

    fn parse_primitive(&mut self) -> std::result::Result<Primitive, ()> {
        let p = match self.peek() {
            Some('Z') => Primitive::Boolean,
            Some('B') => Primitive::Byte,
            Some('C') => Primitive::Char,
            Some('D') => Primitive::Double,
            Some('F') => Primitive::Float,
            Some('I') => Primitive::Int,
            Some('J') => Primitive::Long,
            Some('S') => Primitive::Short,
            Some('V') => Primitive::Void,
            _ => return Err(()),
        };
        self.it.next();
        Ok(p)
    }

    /// Parses `L<name>;`, returning `<name>`. `<name>` must be non-empty
    /// (mirrors `combine`'s `many1`).
    fn parse_object(&mut self) -> std::result::Result<String, ()> {
        if self.peek() != Some('L') {
            return Err(());
        }
        self.it.next();
        let mut name = String::new();
        loop {
            match self.it.next() {
                Some(';') => break,
                Some(c) => name.push(c),
                None => return Err(()),
            }
        }
        if name.is_empty() {
            return Err(());
        }
        Ok(name)
    }

    fn parse_array(&mut self) -> std::result::Result<JavaType, ()> {
        if self.peek() != Some('[') {
            return Err(());
        }
        self.it.next();
        Ok(JavaType::Array(Box::new(self.parse_type()?)))
    }

    fn parse_type(&mut self) -> std::result::Result<JavaType, ()> {
        match self.peek() {
            Some('[') => self.parse_array(),
            Some('L') => Ok(JavaType::Object(self.parse_object()?)),
            Some('(') => self.parse_sig(),
            _ => Ok(JavaType::Primitive(self.parse_primitive()?)),
        }
    }

    fn parse_return(&mut self) -> std::result::Result<ReturnType, ()> {
        match self.peek() {
            Some('[') => self.parse_array().map(|_| ReturnType::Array),
            Some('L') => self.parse_object().map(|_| ReturnType::Object),
            _ => Ok(ReturnType::Primitive(self.parse_primitive()?)),
        }
    }

    fn parse_args(&mut self) -> std::result::Result<Vec<JavaType>, ()> {
        if self.peek() != Some('(') {
            return Err(());
        }
        self.it.next();
        let mut args = Vec::new();
        loop {
            match self.peek() {
                Some(')') => {
                    self.it.next();
                    break;
                }
                None => return Err(()),
                _ => args.push(self.parse_type()?),
            }
        }
        Ok(args)
    }

    fn parse_sig(&mut self) -> std::result::Result<JavaType, ()> {
        let args = self.parse_args()?;
        let ret = self.parse_return()?;
        Ok(JavaType::Method(Box::new(TypeSignature { args, ret })))
    }
}

#[cfg(test)]
mod test {
    use super::*;

    #[test]
    fn test_parser() {
        let inputs = [
            "(Ljava/lang/String;I)V",
            "[Lherp;",
            // fails because the return type does not contain the class name: "(IBVZ)L;"
            // "(IBVZ)Ljava/lang/String;",
        ];

        for each in inputs.iter() {
            let res = JavaType::from_str(each).unwrap();
            println!("{res:#?}");
            let s = format!("{res}");
            assert_eq!(s, *each);
            let res2 = JavaType::from_str(each).unwrap();
            println!("{res2:#?}");
            assert_eq!(res2, res);
        }
    }

    #[test]
    fn test_parser_invalid_signature() {
        let signature = "()Ljava/lang/List"; // no semicolon
        let res = JavaType::from_str(signature);

        match res {
            Ok(any) => {
                panic!("Unexpected result: {}", any);
            }
            Err(err) => {
                assert!(err.to_string().contains("input: ()Ljava/lang/List"));
            }
        }
    }
}
