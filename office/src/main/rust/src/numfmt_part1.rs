#[cfg(test)]
mod tests {
    use super::*;

    fn kinds(f: &NumberFormat) -> Vec<&str> {
        f.date_time_tokens.iter().map(|t| t.kind.as_str()).collect()
    }

    #[test]
    fn general_and_text_formats_have_no_style() {
        assert!(parse("General").is_none());
        assert!(parse("general").is_none());
        assert!(parse("").is_none());
        assert!(parse("@").is_none(), "text format produces no number style");
        assert!(for_builtin(0).is_none());
    }

    #[test]
    fn plain_numeric_formats() {
        let two = parse("0.00").unwrap();
        assert_eq!(two.decimals, 2);
        assert!(!two.grouping && !two.percent);

        let grouped = parse("#,##0.00").unwrap();
        assert_eq!(grouped.decimals, 2);
        assert!(grouped.grouping);

        // No decimal point means zero decimals even though `#` follows nothing.
        assert_eq!(parse("#,##0").unwrap().decimals, 0);
    }

    #[test]
    fn percent_scientific_and_fraction() {
        let pct = parse("0.00%").unwrap();
        assert!(pct.percent);
        assert_eq!(pct.decimals, 2);

        let sci = parse("0.00E+00").unwrap();
        assert!(sci.is_scientific);
        assert_eq!(sci.decimals, 2);

        let frac = parse("# ??/??").unwrap();
        assert!(frac.is_fraction);
        assert_eq!(frac.fraction_denominator_digits, 2);
        assert_eq!(parse("# ?/?").unwrap().fraction_denominator_digits, 1);
    }

    #[test]
    fn a_slash_alone_is_not_a_fraction() {
        // A date separator must not be mistaken for a fraction bar.
        let f = parse("0\" a/b\"").unwrap();
        assert!(!f.is_fraction);
    }

    #[test]
    fn currency_symbols() {
        assert_eq!(parse("[$USD-409]#,##0.00").unwrap().currency_symbol.as_deref(), Some("USD"));
        assert_eq!(parse("$#,##0.00").unwrap().currency_symbol.as_deref(), Some("$"));
        assert_eq!(parse("€#,##0").unwrap().currency_symbol.as_deref(), Some("€"));
        assert_eq!(parse("0.00").unwrap().currency_symbol, None);
    }

    #[test]
    fn quoted_literals_do_not_make_a_number_look_like_a_date() {
        // "days" contains d, a, y, s — every date letter — but it is a literal.
        let f = parse("0\" days\"").unwrap();
        assert!(!f.is_date && !f.is_time, "quoted text must not trigger date detection");
        assert_eq!(f.decimals, 0);
    }

    #[test]
    fn date_formats_produce_ordered_tokens() {
        let f = parse("yyyy-mm-dd").unwrap();
        assert!(f.is_date && !f.is_time);
        assert_eq!(kinds(&f), vec!["year", "text", "month", "text", "day"]);
        assert_eq!(f.date_time_tokens[0].style.as_deref(), Some("long"));
    }

    #[test]
    fn m_is_minutes_after_an_hour_and_month_otherwise() {
        let time = parse("h:mm:ss").unwrap();
        assert_eq!(kinds(&time), vec!["hours", "text", "minutes", "text", "seconds"]);
        assert!(time.is_time && !time.is_date);

        let date = parse("mm-dd-yy").unwrap();
        assert_eq!(kinds(&date)[0], "month");

        // `mm` directly before seconds is minutes even with no hour token — builtin 45.
        let ms = parse("mm:ss").unwrap();
        assert_eq!(kinds(&ms), vec!["minutes", "text", "seconds"]);
    }

    #[test]
    fn a_datetime_with_both_parts_is_a_date() {
        let f = parse("m/d/yy h:mm").unwrap();
        assert!(f.is_date, "mixed date+time is tagged as a date");
        assert!(!f.is_time);
        assert!(kinds(&f).contains(&"hours") && kinds(&f).contains(&"month"));
    }

    #[test]
    fn am_pm_is_recognised_in_both_spellings() {
        assert!(kinds(&parse("h:mm AM/PM").unwrap()).contains(&"am-pm"));
        assert!(kinds(&parse("h:mm A/P").unwrap()).contains(&"am-pm"));
    }

    #[test]
    fn textual_months_and_weekdays() {
        let f = parse("ddd, d mmm yyyy").unwrap();
        let tokens = &f.date_time_tokens;
        let dow = tokens.iter().find(|t| t.kind == "day-of-week").unwrap();
        assert!(dow.textual);
        let month = tokens.iter().find(|t| t.kind == "month").unwrap();
        assert!(month.textual, "mmm is a textual month name");
    }

    #[test]
    fn escaped_and_bracketed_segments() {
        // [Red] is a colour, not content; \- is an escaped literal.
        let f = parse("[Red]0.0").unwrap();
        assert_eq!(f.decimals, 1);
        assert!(!f.is_date);

        // Builtin 46. Stripping the bracketed [h] leaves a leading ":", which survives as a text
        // token — matching the Kotlin, which strips brackets before tokenising for the same
        // reason. The remaining `mm` is still minutes because seconds follow it.
        let elapsed = parse("[h]:mm:ss").unwrap();
        assert_eq!(kinds(&elapsed), vec!["text", "minutes", "text", "seconds"]);
    }

    #[test]
    fn only_the_first_section_is_used() {
        let f = parse("#,##0;[Red](#,##0)").unwrap();
        assert!(f.grouping);
        assert_eq!(f.currency_symbol, None, "the negative section is ignored");
    }

    #[test]
    fn builtins_resolve() {
        assert_eq!(for_builtin(2).unwrap().decimals, 2);
        assert!(for_builtin(9).unwrap().percent);
        assert!(for_builtin(14).unwrap().is_date);
        assert!(for_builtin(21).unwrap().is_time);
        assert!(for_builtin(49).is_none(), "@ is text");
        assert!(for_builtin(9999).is_none(), "unknown ids are General");
        // Locale date builtins fall back to a plain pattern rather than garbage.
        assert!(for_builtin(30).unwrap().is_date);
        assert!(for_builtin(55).unwrap().is_date);
    }

    #[test]
    fn date_time_builtin_ids() {
        for id in [14, 18, 22, 45, 47, 27, 36, 50, 58] {
            assert!(is_date_time_builtin(id), "{id} should be date/time");
        }
        for id in [0, 1, 4, 9, 11, 37, 49] {
            assert!(!is_date_time_builtin(id), "{id} should not be date/time");
        }
    }

    #[test]
    fn malformed_codes_do_not_panic() {
        for code in ["\"unterminated", "\\", "[unclosed", "0.", "/", "[$", "???"] {
            let _ = parse(code);
        }
    }
}
