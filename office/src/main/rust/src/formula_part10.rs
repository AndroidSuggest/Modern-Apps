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
