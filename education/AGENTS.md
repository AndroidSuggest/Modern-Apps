# AGENTS.md — education/ (':education')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `education/` + allowed shared modules. Do not root-scan.

_Install: ./install education (dev by default)._

- Gradle: :education / dir: education/
- Package roots present: ui, data
- Entry files: MainActivity.kt, EducationApplication.kt
- Deps: :library:room, :youpipe:extractor, :library:network
- Metadata: metadata_data/education.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/education/EducationApplication.kt
- com/vayunmathur/education/MainActivity.kt
- com/vayunmathur/education/content/ContentModel.kt
- com/vayunmathur/education/content/ContentRepository.kt
- com/vayunmathur/education/content/ContentValidator.kt
- com/vayunmathur/education/content/Question.kt
- com/vayunmathur/education/data/Deadline.kt
- com/vayunmathur/education/data/EducationDatabase.kt
- com/vayunmathur/education/data/EducationRepository.kt
- com/vayunmathur/education/data/Learner.kt
- com/vayunmathur/education/data/SkillProgress.kt
- com/vayunmathur/education/ui/BadgesPage.kt
- com/vayunmathur/education/ui/Common.kt
- com/vayunmathur/education/ui/CoursePage.kt
- com/vayunmathur/education/ui/ExplorerCoursePage.kt
- com/vayunmathur/education/ui/ExplorerHomePage.kt
- com/vayunmathur/education/ui/HomePage.kt
- com/vayunmathur/education/ui/K2DragMatch.kt
- com/vayunmathur/education/ui/K2HomePage.kt
- com/vayunmathur/education/ui/K2LessonPage.kt
- com/vayunmathur/education/ui/K2QuizPage.kt
- com/vayunmathur/education/ui/K2RewardPage.kt
- com/vayunmathur/education/ui/LessonPage.kt
- com/vayunmathur/education/ui/ParentGatePage.kt
- com/vayunmathur/education/ui/ParentPage.kt
- com/vayunmathur/education/ui/QuizPage.kt
- com/vayunmathur/education/ui/QuizQuestionInputs.kt
- com/vayunmathur/education/ui/ResultsPage.kt
- com/vayunmathur/education/ui/Shell.kt
- com/vayunmathur/education/ui/TracingCanvas.kt
- com/vayunmathur/education/ui/UnitPage.kt
- com/vayunmathur/education/ui/VideoPlayerPage.kt
- com/vayunmathur/education/util/EducationAchievements.kt
- com/vayunmathur/education/util/EducationDownloader.kt
- com/vayunmathur/education/util/EducationUiContract.kt
- com/vayunmathur/education/util/EducationViewModel.kt
- com/vayunmathur/education/util/Narrator.kt
- com/vayunmathur/education/util/VideoExtractor.kt

## Verify (this module only)
```
./gradlew :education:compileDevKotlin
./gradlew :education:lint
./gradlew :education:checkMetadata
```


