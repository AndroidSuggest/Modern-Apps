package com.vayunmathur.contacts.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import com.vayunmathur.contacts.R

@Composable
fun NameSuffixChooser(nameSuffix: String, sharedKey: Any? = null, onNameSuffixChange: (String) -> Unit) {
    NameAffixChooser(
        value = nameSuffix,
        placeholder = stringResource(R.string.name_suffix),
        options = stringArrayResource(R.array.name_suffixes).toList(),
        sharedKey = sharedKey,
        onValueChange = onNameSuffixChange,
    )
}
