# AGENTS.md — email/ (':email')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `email/` + allowed shared modules. Do not root-scan.

_Install: ./install email (dev by default)._

- Gradle: :email / dir: email/
- Package roots present: ui, data, platform, network, intents, widget
- Entry files: MainActivity.kt, Route.kt, Navigation.kt
- Deps: :library:room, :library:widgets, :library:network
- Metadata: metadata_data/email.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/email/MainActivity.kt
- com/vayunmathur/email/Navigation.kt
- com/vayunmathur/email/Route.kt
- com/vayunmathur/email/data/CredentialCrypto.kt
- com/vayunmathur/email/data/DateMillisBackfill.kt
- com/vayunmathur/email/data/EmailDao.kt
- com/vayunmathur/email/data/EmailDatabase.kt
- com/vayunmathur/email/data/EmailModels.kt
- com/vayunmathur/email/data/EmailRepository.kt
- com/vayunmathur/email/data/EmailSettings.kt
- com/vayunmathur/email/data/EmailSyncState.kt
- com/vayunmathur/email/data/EmailSyncWorker.kt
- com/vayunmathur/email/data/ImapIdleRetryWorker.kt
- com/vayunmathur/email/data/ImapIdleService.kt
- com/vayunmathur/email/data/OutboxSendWorker.kt
- com/vayunmathur/email/data/OutlookOAuth.kt
- com/vayunmathur/email/data/PeekContentBackfill.kt
- com/vayunmathur/email/data/ProviderPresets.kt
- com/vayunmathur/email/data/SnoozeWorker.kt
- com/vayunmathur/email/intents/EmailDataMapper.kt
- com/vayunmathur/email/intents/GetRecentIntent.kt
- com/vayunmathur/email/intents/SearchIntent.kt
- com/vayunmathur/email/network/imap/ImapAuthException.kt
- com/vayunmathur/email/network/imap/ImapClient.kt
- com/vayunmathur/email/network/imap/ImapParser.kt
- com/vayunmathur/email/network/imap/MimeParser.kt
- com/vayunmathur/email/network/imap/RawImapConnection.kt
- com/vayunmathur/email/network/imap/TrustAll.kt
- com/vayunmathur/email/network/smtp/MimeBuilder.kt
- com/vayunmathur/email/network/smtp/RawSmtpConnection.kt
- com/vayunmathur/email/network/smtp/SmtpClient.kt
- com/vayunmathur/email/platform/AppLifecycleTracker.kt
- com/vayunmathur/email/platform/BootReceiver.kt
- com/vayunmathur/email/platform/EmailClientFlag.kt
- com/vayunmathur/email/platform/EmailManager.kt
- com/vayunmathur/email/platform/EmailNotificationActionReceiver.kt
- com/vayunmathur/email/platform/EmailNotifications.kt
- com/vayunmathur/email/platform/EmailUiContract.kt
- com/vayunmathur/email/platform/EmailViewModel.kt
- com/vayunmathur/email/platform/EmlUtils.kt
- com/vayunmathur/email/platform/IntentState.kt
- com/vayunmathur/email/ui/AddAccountScreen.kt
- com/vayunmathur/email/ui/AddAccountSection.kt
- com/vayunmathur/email/ui/AttachmentItem.kt
- com/vayunmathur/email/ui/ComposerAttachments.kt
- com/vayunmathur/email/ui/ComposerHeader.kt
- com/vayunmathur/email/ui/ComposerHelpers.kt
- com/vayunmathur/email/ui/ComposerScreen.kt
- com/vayunmathur/email/ui/DetailItem.kt
- com/vayunmathur/email/ui/DraftsScreen.kt
- com/vayunmathur/email/ui/EmailUiHelpers.kt
- com/vayunmathur/email/ui/EmlViewerScreen.kt
- com/vayunmathur/email/ui/FolderList.kt
- com/vayunmathur/email/ui/MainContent.kt
- com/vayunmathur/email/ui/MessageListScreen.kt
- com/vayunmathur/email/ui/MessageThreadScreen.kt
- com/vayunmathur/email/ui/OAuthActivity.kt
- com/vayunmathur/email/ui/OutboxScreen.kt
- com/vayunmathur/email/ui/SettingsScreen.kt
- com/vayunmathur/email/ui/composer/CidImageSpan.kt
- com/vayunmathur/email/ui/composer/EmailHtmlEditor.kt
- com/vayunmathur/email/ui/composer/EmailHtmlEditorController.kt
- com/vayunmathur/email/ui/composer/EmailHtmlSerializer.kt
- com/vayunmathur/email/ui/composer/EmailHtmlTagHandler.kt
- com/vayunmathur/email/ui/composer/InlineImage.kt
- com/vayunmathur/email/widget/EmailWidget.kt
- com/vayunmathur/email/widget/EmailWidgetReceiver.kt

## Verify (this module only)
```
./gradlew :email:compileDevKotlin
./gradlew :email:lint
./gradlew :email:checkMetadata
```


