# AGENTS.md — calendar/ (':calendar')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `calendar/` + allowed shared modules. Do not root-scan.

_Install: ./install calendar (dev by default)._

- Gradle: :calendar / dir: calendar/
- Package roots present: ui, data, intents
- Entry files: MainActivity.kt
- Deps: :library:widgets
- Metadata: metadata_data/calendar.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/calendar/MainActivity.kt
- com/vayunmathur/calendar/data/Calendar.kt
- com/vayunmathur/calendar/data/Event.kt
- com/vayunmathur/calendar/data/Instance.kt
- com/vayunmathur/calendar/data/ReminderMirror.kt
- com/vayunmathur/calendar/glance/CalendarGlanceWidget.kt
- com/vayunmathur/calendar/glance/CalendarGlanceWidgetReceiver.kt
- com/vayunmathur/calendar/glance/CalendarMonthGlanceWidget.kt
- com/vayunmathur/calendar/glance/CalendarMonthGlanceWidgetReceiver.kt
- com/vayunmathur/calendar/intents/GetIntent.kt
- com/vayunmathur/calendar/intents/InsertIntent.kt
- com/vayunmathur/calendar/ui/AgendaView.kt
- com/vayunmathur/calendar/ui/CalendarMonthView.kt
- com/vayunmathur/calendar/ui/CalendarPagerView.kt
- com/vayunmathur/calendar/ui/CalendarScreen.kt
- com/vayunmathur/calendar/ui/CalendarSelector.kt
- com/vayunmathur/calendar/ui/CalendarViewHelpers.kt
- com/vayunmathur/calendar/ui/EditEventDateTime.kt
- com/vayunmathur/calendar/ui/EditEventReminders.kt
- com/vayunmathur/calendar/ui/EditEventRow.kt
- com/vayunmathur/calendar/ui/EditEventScreen.kt
- com/vayunmathur/calendar/ui/EventPositioner.kt
- com/vayunmathur/calendar/ui/EventScreen.kt
- com/vayunmathur/calendar/ui/HolidayCalendarsScreen.kt
- com/vayunmathur/calendar/ui/HourScale.kt
- com/vayunmathur/calendar/ui/IcsImport.kt
- com/vayunmathur/calendar/ui/ImportIcsScreen.kt
- com/vayunmathur/calendar/ui/SettingsScreen.kt
- com/vayunmathur/calendar/ui/SummaryEventItem.kt
- com/vayunmathur/calendar/ui/SummaryGrid.kt
- com/vayunmathur/calendar/ui/WeekHeaderRow.kt
- com/vayunmathur/calendar/ui/dialogs/CalendarPickerDialog.kt
- com/vayunmathur/calendar/ui/dialogs/CalendarSetDateDialog.kt
- com/vayunmathur/calendar/ui/dialogs/RecurrenceDates.kt
- com/vayunmathur/calendar/ui/dialogs/RecurrenceDialog.kt
- com/vayunmathur/calendar/ui/dialogs/RecurrenceDialogComponents.kt
- com/vayunmathur/calendar/ui/dialogs/SettingsAddCalendarDialog.kt
- com/vayunmathur/calendar/ui/dialogs/SettingsChangeColorDialog.kt
- com/vayunmathur/calendar/ui/dialogs/SettingsDefaultRemindersDialog.kt
- com/vayunmathur/calendar/ui/dialogs/SettingsDeleteCalendarDialog.kt
- com/vayunmathur/calendar/ui/dialogs/SettingsRenameCalendarDialog.kt
- com/vayunmathur/calendar/ui/dialogs/TimezonePickerDialog.kt
- com/vayunmathur/calendar/util/BootReceiver.kt
- com/vayunmathur/calendar/util/CalendarUiContract.kt
- com/vayunmathur/calendar/util/CalendarViewModel.kt
- com/vayunmathur/calendar/util/DateFormats.kt
- com/vayunmathur/calendar/util/HolidayCalendarManager.kt
- com/vayunmathur/calendar/util/HolidayData.kt
- com/vayunmathur/calendar/util/IcsExport.kt
- com/vayunmathur/calendar/util/RecurrenceParams.kt
- com/vayunmathur/calendar/util/ReminderReceiver.kt
- com/vayunmathur/calendar/util/ReminderScheduler.kt
- com/vayunmathur/calendar/util/RRule.kt

## Verify (this module only)
```
./gradlew :calendar:compileDevKotlin
./gradlew :calendar:lint
./gradlew :calendar:checkMetadata
```


