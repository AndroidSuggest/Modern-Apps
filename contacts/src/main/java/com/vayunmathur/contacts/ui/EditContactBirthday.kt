package com.vayunmathur.contacts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.data.formatDisplay
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconRemoveCircle
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.ResultEffect
import java.util.Locale
import kotlinx.datetime.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditContactBirthday(
    backStack: NavBackStack<Route>,
    birthday: LocalDate?,
    setBirthday: (LocalDate?) -> Unit
) {
    ResultEffect<LocalDate>("birthday") {
        setBirthday(it)
    }
    Box {
        OutlinedTextField(
            value = birthday?.formatDisplay(LocalConfiguration.current.locales[0] ?: Locale.getDefault()) ?: "",
            onValueChange = { },
            readOnly = true,
            label = {Text(stringResource(R.string.birthday))},
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                // There is only ever one birthday, so the row cannot be removed. The button clears the
                // value instead, and is absent while there is nothing to clear.
                if (birthday != null) {
                    IconButton(onClick = { setBirthday(null) }) {
                        IconRemoveCircle()
                    }
                }
            }
        )
        // The field is read-only and opens the picker, so the whole row is the tap target -
        // except the trailing clear button, which needs its own clicks to reach it.
        Row(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { backStack.add(Route.EventDatePickerDialog("birthday", birthday)) }
            )
            // Only reserved when the button is actually there, or the right edge of an empty field
            // would not open the picker.
            if (birthday != null) Spacer(Modifier.width(RemoveButtonWidth))
        }
    }

    Spacer(Modifier.height(8.dp))
}
