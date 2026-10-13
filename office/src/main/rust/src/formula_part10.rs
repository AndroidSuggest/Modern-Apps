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
    fn named_ranges() {
        // Named range MyRange -> Sheet1.A1:A3, single cell MyCell -> Sheet1.B2.
        // The probe formula lives in D1 (outside the ranges) to avoid self-reference.
        let at = |f: &str| {
            let wjson = format!(
                "{{\"sheets\":[{{\"name\":\"Sheet1\",\"rows\":[\
                {{\"cells\":[{{\"text\":\"1\",\"numberValue\":1}},{{\"text\":\"10\",\"numberValue\":10}},{{\"text\":\"\"}},{{\"text\":\"\",\"formula\":\"{}\"}}]}},\
                {{\"cells\":[{{\"text\":\"2\",\"numberValue\":2}},{{\"text\":\"20\",\"numberValue\":20}}]}},\
                {{\"cells\":[{{\"text\":\"3\",\"numberValue\":3}},{{\"text\":\"30\",\"numberValue\":30}}]}}\
                ]}}],\"namedRanges\":[\
                {{\"name\":\"MyRange\",\"addr\":\"Sheet1.A1:Sheet1.A3\"}},\
                {{\"name\":\"MyCell\",\"addr\":\"Sheet1.B2\"}}\
                ]}}",
                f.replace('"', "\\\"")
            );
            Workbook::from_json(&wjson, 1_700_000_000_000)
                .unwrap()
                .display_value(0, 0, 3)
        };
        // Bare names resolve (scalar + range forms).
        assert_eq!(at("MyRange"), "1");
        assert_eq!(at("of:=SUM(MyRange)"), "6");
        assert_eq!(at("of:=MyCell+1"), "21");
        assert_eq!(at("of:=AVERAGE(MyRange)"), "2");
        // Case-insensitive.
        assert_eq!(at("of:=SUM(myrange)"), "6");
        // Unknown names still #NAME?.
        assert_eq!(at("of:=SUM(Nope)"), "#NAME?");
        // A name must be bare: `MyRange(` is a (missing) function call.
        assert_eq!(at("of:=MyRange(1)"), "#NAME?");
    }

    #[test]
    fn whole_span_ranges() {
        // Full-span expansions (from Excel whole-column/row refs) evaluate over data.
        // Column span excludes the probe cell's column, so no self-reference cycle.
        assert_eq!(eval("of:=SUM([.A1:.A1048576])"), "6");
        // Row span: probe formula in A4 sums rows 1..3 across A..XFD (49k cells,
        // mostly blank). 1+10+2+20+3+30 = 66.
        let wjson = "{\"sheets\":[{\"name\":\"Sheet1\",\"rows\":[\
            {\"cells\":[{\"text\":\"1\",\"numberValue\":1},{\"text\":\"10\",\"numberValue\":10}]},\
            {\"cells\":[{\"text\":\"2\",\"numberValue\":2},{\"text\":\"20\",\"numberValue\":20}]},\
            {\"cells\":[{\"text\":\"3\",\"numberValue\":3},{\"text\":\"30\",\"numberValue\":30}]},\
            {\"cells\":[{\"text\":\"\",\"formula\":\"of:=SUM([.A1:.XFD3])\"}]}\
            ]}]}";
        let wb = Workbook::from_json(wjson, 1_700_000_000_000).unwrap();
        assert_eq!(wb.display_value(0, 3, 0), "66");
    }

    #[test]
    fn xlookup() {
        // Keys A1..A3 = 1,2,3 ; values B1..B3 = 10,20,30.
        assert_eq!(eval("of:=XLOOKUP(2;[.A1:.A3];[.B1:.B3])"), "20");
        assert_eq!(eval("of:=XLOOKUP(9;[.A1:.A3];[.B1:.B3])"), "#N/A");
        assert_eq!(eval("of:=XLOOKUP(9;[.A1:.A3];[.B1:.B3];\"nf\")"), "nf");
        // Exact-or-next-smaller: 2.5 -> key 2 -> 20.
        assert_eq!(eval("of:=XLOOKUP(2.5;[.A1:.A3];[.B1:.B3];;-1)"), "20");
        // Exact-or-next-larger: 2.5 -> key 3 -> 30.
        assert_eq!(eval("of:=XLOOKUP(2.5;[.A1:.A3];[.B1:.B3];;1)"), "30");
        // Last-to-first: duplicate keys would pick the later one; here sanity.
        assert_eq!(eval("of:=XLOOKUP(2;[.A1:.A3];[.B1:.B3];;;-1)"), "20");
        // Binary search on the sorted keys.
        assert_eq!(eval("of:=XLOOKUP(3;[.A1:.A3];[.B1:.B3];;;2)"), "30");
        assert_eq!(eval("of:=XLOOKUP(9;[.A1:.A3];[.B1:.B3];;0;2)"), "#N/A");
    }

    #[test]
    fn dynamic_arrays() {
        // FILTER: B > 15 keeps rows 2,3 -> values 2,3 (first displays).
        assert_eq!(eval("of:=FILTER([.A1:.A3];[.B1:.B3]>15)"), "2");
        assert_eq!(eval("of:=SUM(FILTER([.A1:.A3];[.B1:.B3]>15))"), "5");
        assert_eq!(eval("of:=COUNT(FILTER([.A1:.A3];[.B1:.B3]>15))"), "2");
        assert_eq!(eval("of:=FILTER([.A1:.A3];[.B1:.B3]>99)"), "#CALC!");
        assert_eq!(eval("of:=FILTER([.A1:.A3];[.B1:.B3]>99;\"none\")"), "none");
        // SORT / SORTBY / UNIQUE.
        assert_eq!(eval("of:=SUM(SORT([.B1:.B3];1;-1))"), "60");
        assert_eq!(eval("of:=SORT([.B1:.B3];1;-1)"), "30");
        assert_eq!(eval("of:=SUM(SORTBY([.A1:.A3];[.B1:.B3];-1))"), "6");
        assert_eq!(eval("of:=SORTBY([.A1:.A3];[.B1:.B3];-1)"), "3");
        assert_eq!(eval("of:=SUM(UNIQUE([.A1:.A3]))"), "6");
        // SEQUENCE composes with aggregations; bare displays first element.
        assert_eq!(eval("of:=SUM(SEQUENCE(4))"), "10");
        assert_eq!(eval("of:=SEQUENCE(4)"), "1");
        assert_eq!(eval("of:=SEQUENCE(2;2)"), "1");
        assert_eq!(eval("of:=SUM(SEQUENCE(2;2;10;10))"), "100");
        // LET binds names then evaluates.
        assert_eq!(eval("of:=LET(x;2;x*10)"), "20");
        assert_eq!(eval("of:=LET(x;2;y;3;x+y)"), "5");
        assert_eq!(eval("of:=LET(x;[.A1]+[.A2];x*2)"), "6");
        assert_eq!(eval("of:=LET(s;\"hi\";s&\"!\")"), "hi!");
        // ROWS sees through spilled arrays.
        assert_eq!(eval("of:=ROWS(FILTER([.A1:.A3];[.B1:.B3]>0))"), "3");
        // OFFSET with height/width spills; SUM composes.
        assert_eq!(eval("of:=SUM(OFFSET([.A1];0;0;3;1))"), "6");
        assert_eq!(eval("of:=OFFSET([.A1];1;1)"), "20");
        // LAMBDA is recognized but unsupported here.
        assert_eq!(eval("of:=LAMBDA(x;x+1)"), "#VALUE!");
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
