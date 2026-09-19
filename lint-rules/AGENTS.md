# AGENTS.md — lint-rules/ (':lint-rules')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `lint-rules/` + allowed shared modules. Do not root-scan.

- Gradle: :lint-rules / dir: lint-rules/
- Package roots present: 
- Entry files: 
- Deps: 
- Metadata: metadata_data/lint-rules.md (MISSING)
- Rust: no / screenshotTest: no

## Key files

- com/vayunmathur/lint/DirectBuildDatabaseDetector.kt
- com/vayunmathur/lint/DirectComposeAnimationDetector.kt
- com/vayunmathur/lint/FileLengthDetector.kt
- com/vayunmathur/lint/LintPackageExclusions.kt
- com/vayunmathur/lint/ModernAppsIssueRegistry.kt
- com/vayunmathur/lint/OneComposablePerFileDetector.kt
- com/vayunmathur/lint/PackageStructureDetector.kt
- com/vayunmathur/lint/RawScaffoldInAppDetector.kt
- com/vayunmathur/lint/Room2UsageDetector.kt
- com/vayunmathur/lint/ToastDetector.kt
- com/vayunmathur/lint/WindowInsetsInReusableComponentDetector.kt

## Verify (this module only)
```
./gradlew :lint-rules:compileDevKotlin
./gradlew :lint-rules:lint
```


