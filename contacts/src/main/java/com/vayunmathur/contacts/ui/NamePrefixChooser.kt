package com.vayunmathur.contacts.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import com.vayunmathur.contacts.R

@Composable
fun NamePrefixChooser(namePrefix: String, sharedKey: Any? = null, onNamePrefixChange: (String) -> Unit) {
    NameAffixChooser(
        value = namePrefix,
        placeholder = stringResource(R.string.name_prefix),
        options = stringArrayResource(R.array.name_prefixes).toList(),
        sharedKey = sharedKey,
        onValueChange = onNamePrefixChange,
    )
}
