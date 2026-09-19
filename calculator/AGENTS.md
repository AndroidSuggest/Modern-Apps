# AGENTS.md — calculator/ (':calculator')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `calculator/` + allowed shared modules. Do not root-scan.

_Install: ./install calculator (dev by default)._

- Gradle: :calculator / dir: calculator/
- Package roots present: ui, widget
- Entry files: MainActivity.kt
- Deps: :library:network, :library:widgets
- Metadata: metadata_data/calculator.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/calculator/MainActivity.kt
- com/vayunmathur/calculator/ui/CalculatorDialogs.kt
- com/vayunmathur/calculator/ui/CalculatorKeypad.kt
- com/vayunmathur/calculator/ui/CalculatorPage.kt
- com/vayunmathur/calculator/ui/GraphPage.kt
- com/vayunmathur/calculator/ui/UnitConverterPage.kt
- com/vayunmathur/calculator/util/CalculatorUiContract.kt
- com/vayunmathur/calculator/util/CalculatorViewModel.kt
- com/vayunmathur/calculator/util/CurrencyApi.kt
- com/vayunmathur/calculator/util/Expression.kt
- com/vayunmathur/calculator/util/Formatting.kt
- com/vayunmathur/calculator/util/GraphAnalysis.kt
- com/vayunmathur/calculator/util/Units.kt
- com/vayunmathur/calculator/widget/CalculatorGlanceWidget.kt
- com/vayunmathur/calculator/widget/CalculatorGlanceWidgetReceiver.kt
- com/vayunmathur/calculator/widget/CalculatorKeyAction.kt
- com/vayunmathur/calculator/widget/CalculatorWidgetSettingsActivity.kt
- com/vayunmathur/calculator/widget/UnitsGlanceWidget.kt
- com/vayunmathur/calculator/widget/UnitsGlanceWidgetReceiver.kt
- com/vayunmathur/calculator/widget/UnitsWidgetActions.kt

## Verify (this module only)
```
./gradlew :calculator:compileDevKotlin
./gradlew :calculator:lint
./gradlew :calculator:checkMetadata
```


