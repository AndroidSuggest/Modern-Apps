# AGENTS.md — contacts/ (':contacts')

> Scoped supplement. Global rules in root `AGENTS.md` apply. Stay inside `contacts/` + allowed shared modules. Do not root-scan.

_Install: ./install contacts (dev by default)._

- Gradle: :contacts / dir: contacts/
- Package roots present: ui, data, intents, auth
- Entry files: MainActivity.kt
- Deps: 
- Metadata: metadata_data/contacts.md (present)
- Rust: no / screenshotTest: yes

## Key files

- com/vayunmathur/contacts/MainActivity.kt
- com/vayunmathur/contacts/auth/Authenticator.kt
- com/vayunmathur/contacts/data/Contact.kt
- com/vayunmathur/contacts/data/ContactGroup.kt
- com/vayunmathur/contacts/data/ContactPrefill.kt
- com/vayunmathur/contacts/data/SimContacts.kt
- com/vayunmathur/contacts/intents/GetIntent.kt
- com/vayunmathur/contacts/intents/InsertIntent.kt
- com/vayunmathur/contacts/ui/AvatarColors.kt
- com/vayunmathur/contacts/ui/ContactDetailsComms.kt
- com/vayunmathur/contacts/ui/ContactDetailsFormat.kt
- com/vayunmathur/contacts/ui/ContactDetailsHeader.kt
- com/vayunmathur/contacts/ui/ContactDetailsPage.kt
- com/vayunmathur/contacts/ui/ContactDetailsRows.kt
- com/vayunmathur/contacts/ui/ContactDetailsSections.kt
- com/vayunmathur/contacts/ui/ContactItem.kt
- com/vayunmathur/contacts/ui/ContactItemPick.kt
- com/vayunmathur/contacts/ui/ContactList.kt
- com/vayunmathur/contacts/ui/ContactListPick.kt
- com/vayunmathur/contacts/ui/ContactListScreen.kt
- com/vayunmathur/contacts/ui/ContactUiHelpers.kt
- com/vayunmathur/contacts/ui/CropPhotoScreen.kt
- com/vayunmathur/contacts/ui/CropPhotoSection.kt
- com/vayunmathur/contacts/ui/EditContactAccountSection.kt
- com/vayunmathur/contacts/ui/EditContactBirthday.kt
- com/vayunmathur/contacts/ui/EditContactDateDetails.kt
- com/vayunmathur/contacts/ui/EditContactDetailGroups.kt
- com/vayunmathur/contacts/ui/EditContactGroupSection.kt
- com/vayunmathur/contacts/ui/EditContactNameSection.kt
- com/vayunmathur/contacts/ui/EditContactPage.kt
- com/vayunmathur/contacts/ui/EditContactPhotoSection.kt
- com/vayunmathur/contacts/ui/FavoritesHeader.kt
- com/vayunmathur/contacts/ui/GroupedContactSection.kt
- com/vayunmathur/contacts/ui/GroupsPage.kt
- com/vayunmathur/contacts/ui/ImportActivity.kt
- com/vayunmathur/contacts/ui/ImportVcfScreen.kt
- com/vayunmathur/contacts/ui/InsertOrEditContactScreen.kt
- com/vayunmathur/contacts/ui/LetterHeader.kt
- com/vayunmathur/contacts/ui/NamePrefixChooser.kt
- com/vayunmathur/contacts/ui/NameSuffixChooser.kt
- com/vayunmathur/contacts/ui/SettingsPage.kt
- com/vayunmathur/contacts/ui/dialogs/AddAccountDialog.kt
- com/vayunmathur/contacts/ui/dialogs/AddToGroupDialog.kt
- com/vayunmathur/contacts/ui/dialogs/EventDatePickerDialog.kt
- com/vayunmathur/contacts/ui/dialogs/EventDeleteConfirmDialog.kt
- com/vayunmathur/contacts/util/CalendarSyncHelper.kt
- com/vayunmathur/contacts/util/CalendarWorker.kt
- com/vayunmathur/contacts/util/ContactSorting.kt
- com/vayunmathur/contacts/util/ContactsUiContract.kt
- com/vayunmathur/contacts/util/ContactViewModel.kt
- com/vayunmathur/contacts/util/ContactViewModelAccounts.kt
- com/vayunmathur/contacts/util/ContactViewModelDraft.kt
- com/vayunmathur/contacts/util/PackageUtils.kt
- com/vayunmathur/contacts/util/VcfUtils.kt

## Verify (this module only)
```
./gradlew :contacts:compileDevKotlin
./gradlew :contacts:lint
./gradlew :contacts:checkMetadata
```


