# AGENTS.md — notes/ (':notes')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `notes/` + allowed shared modules. Do not root-scan.

_Install: ./install notes (dev by default)._

- Gradle: :notes / dir: notes/
- Package roots present: ui, data, platform, intents
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:room, :library:ink, :library:image
- Metadata: metadata_data/notes.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/notes/MainActivity.kt
- com/vayunmathur/notes/Navigation.kt
- com/vayunmathur/notes/Route.kt
- com/vayunmathur/notes/data/Note.kt
- com/vayunmathur/notes/data/NoteBlock.kt
- com/vayunmathur/notes/data/NoteDatabase.kt
- com/vayunmathur/notes/data/NotesRepository.kt
- com/vayunmathur/notes/intents/GetIntent.kt
- com/vayunmathur/notes/intents/InsertIntent.kt
- com/vayunmathur/notes/platform/AppBackupAgent.kt
- com/vayunmathur/notes/platform/NoteImageStore.kt
- com/vayunmathur/notes/platform/NoteMarkdownExporter.kt
- com/vayunmathur/notes/platform/NotesUiContract.kt
- com/vayunmathur/notes/platform/NotesViewModel.kt
- com/vayunmathur/notes/ui/ExternalNoteScreen.kt
- com/vayunmathur/notes/ui/InkEditorPage.kt
- com/vayunmathur/notes/ui/NotePage.kt
- com/vayunmathur/notes/ui/NoteScreen.kt
- com/vayunmathur/notes/ui/NotesListPage.kt
- com/vayunmathur/notes/ui/NotesListScreen.kt
- com/vayunmathur/notes/ui/components/NoteEditorComponents.kt

## Verify (this module only)
```
./gradlew :notes:compileDevKotlin
./gradlew :notes:lint
./gradlew :notes:checkMetadata
```


