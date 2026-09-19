# AGENTS.md — code/ (':code')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `code/` + allowed shared modules. Do not root-scan.

_Install: ./install code (dev by default)._

- Gradle: :code / dir: code/
- Package roots present: ui
- Entry files: MainActivity.kt
- Deps: 
- Metadata: metadata_data/code.md (present)
- Rust: yes / screenshotTest: yes

## Key files

- com/vayunmathur/code/MainActivity.kt
- com/vayunmathur/code/syntax/SyntaxHighlighting.kt
- com/vayunmathur/code/ui/CodeEditor.kt
- com/vayunmathur/code/ui/CodeEditorCanvas.kt
- com/vayunmathur/code/ui/CodeEditorChrome.kt
- com/vayunmathur/code/ui/CodeEditorContent.kt
- com/vayunmathur/code/ui/CodeEditorDraw.kt
- com/vayunmathur/code/ui/CodeEditorGestures.kt
- com/vayunmathur/code/ui/CodeEditorInput.kt
- com/vayunmathur/code/ui/CodeEditorKeys.kt
- com/vayunmathur/code/ui/CodeEditorView.kt
- com/vayunmathur/code/ui/CodeWideLayout.kt
- com/vayunmathur/code/ui/CommandPalette.kt
- com/vayunmathur/code/ui/DiffView.kt
- com/vayunmathur/code/ui/EditorBody.kt
- com/vayunmathur/code/ui/EditorChrome.kt
- com/vayunmathur/code/ui/EditorScreen.kt
- com/vayunmathur/code/ui/EditorToolbar.kt
- com/vayunmathur/code/ui/FileTreePane.kt
- com/vayunmathur/code/ui/FolderBrowserPage.kt
- com/vayunmathur/code/ui/GitPage.kt
- com/vayunmathur/code/ui/GitPageSection.kt
- com/vayunmathur/code/ui/Minimap.kt
- com/vayunmathur/code/ui/PreviewPage.kt
- com/vayunmathur/code/ui/SearchPage.kt
- com/vayunmathur/code/ui/SettingsPage.kt
- com/vayunmathur/code/ui/SnippetsPage.kt
- com/vayunmathur/code/ui/TerminalPage.kt
- com/vayunmathur/code/util/CodeUiContract.kt
- com/vayunmathur/code/util/Completion.kt
- com/vayunmathur/code/util/Diagnostics.kt
- com/vayunmathur/code/util/DiffModel.kt
- com/vayunmathur/code/util/EditorDocument.kt
- com/vayunmathur/code/util/EditorFileTreeOps.kt
- com/vayunmathur/code/util/EditorGitOps.kt
- com/vayunmathur/code/util/EditorInput.kt
- com/vayunmathur/code/util/EditorPrefs.kt
- com/vayunmathur/code/util/EditorSaveOps.kt
- com/vayunmathur/code/util/EditorSearchOps.kt
- com/vayunmathur/code/util/EditorTabs.kt
- com/vayunmathur/code/util/EditorTerminalOps.kt
- com/vayunmathur/code/util/EditorViewModel.kt
- com/vayunmathur/code/util/FileFiles.kt
- com/vayunmathur/code/util/FoldRegions.kt
- com/vayunmathur/code/util/Formatting.kt
- com/vayunmathur/code/util/FuzzyMatch.kt
- com/vayunmathur/code/util/GitIgnore.kt
- com/vayunmathur/code/util/GitRepo.kt
- com/vayunmathur/code/util/Markdown.kt
- com/vayunmathur/code/util/MergeConflicts.kt
- com/vayunmathur/code/util/Outline.kt
- com/vayunmathur/code/util/TerminalSession.kt
- com/vayunmathur/code/util/TextEncoding.kt
- com/vayunmathur/code/util/TreeSitterNative.kt

## Verify (this module only)
```
./gradlew :code:compileDevKotlin
./gradlew :code:lint
./gradlew :code:checkMetadata
```


