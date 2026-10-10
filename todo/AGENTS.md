# AGENTS.md - todo/ (':todo')
> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `todo/` + allowed shared modules. Do not root-scan.
_Install: ./install todo (dev by default)._
- Gradle: :todo / dir: todo/
- Package roots present: ui, data, platform, sync
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:room
- Metadata: metadata_data/todo.md (present)
- Rust: no / screenshotTest: yes
## Key files
- com/vayunmathur/todo/MainActivity.kt
- com/vayunmathur/todo/Navigation.kt
- com/vayunmathur/todo/Route.kt
- com/vayunmathur/todo/data/Todo.kt
- com/vayunmathur/todo/data/TodoDatabase.kt
- com/vayunmathur/todo/data/TodoRepository.kt
- com/vayunmathur/todo/platform/AppBackupAgent.kt
- com/vayunmathur/todo/platform/TodoUiContract.kt
- com/vayunmathur/todo/platform/TodoViewModel.kt
- com/vayunmathur/todo/sync/RelayTodoProvider.kt
- com/vayunmathur/todo/ui/TodoDetailPage.kt
- com/vayunmathur/todo/ui/TodoDetailScreen.kt
- com/vayunmathur/todo/ui/TodoListPage.kt
- com/vayunmathur/todo/ui/TodoListScreen.kt
## Verify (this module only)
```
./gradlew :todo:compileDevKotlin
./gradlew :todo:lint
./gradlew :todo:checkMetadata
```
