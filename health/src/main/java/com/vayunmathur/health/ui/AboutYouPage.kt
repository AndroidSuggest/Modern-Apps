package com.vayunmathur.health.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.Route
import com.vayunmathur.health.data.PregnancyStatus
import com.vayunmathur.health.data.ProfileAnswer
import com.vayunmathur.health.data.SmokingStatus
import com.vayunmathur.health.domain.SocialHistoryQuestions
import com.vayunmathur.health.platform.MedicalViewModel
import com.vayunmathur.health.platform.clearSocialHistoryAnswer
import com.vayunmathur.health.platform.importFromHealthConnect
import com.vayunmathur.health.platform.setPregnancyStatus
import com.vayunmathur.health.platform.setSmokingStatus
import com.vayunmathur.health.platform.setSocialHistoryAnswer
import com.vayunmathur.health.ui.components.AnswerPickerDialog
import com.vayunmathur.health.ui.components.MedicalStorageNotice
import com.vayunmathur.health.ui.components.PickerField
import com.vayunmathur.health.ui.components.SectionLabel
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.SettingsExposedSelectRow
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.dialog.DateSelection
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.ResultEffect
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

private const val DUE_DATE_KEY = "about-you/due-date"

/**
 * The standing facts about the user: the things Health Connect models as observations of a *current
 * state* rather than events.
 *
 * Three grouped cards rather than a card per question. Each question is one row: the question in
 * full as the row title, the current answer beneath it, tapping opens the answers. That keeps the
 * instrument's own wording — which is the measurement, not decoration — without the heading, prompt
 * paragraph and recorded-on line that turned a short form into several screens of scrolling.
 *
 * Every question starts at *Not answered* and can be returned to it. Defaulting to the first real
 * option would put "Never" against alcohol for someone who has never opened this screen, and the
 * record would then assert something the user never said.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutYouPage(backStack: NavBackStack<Route>, viewModel: MedicalViewModel) {
    val profile by viewModel.profile.collectAsState()
    val answers by viewModel.profileAnswers.collectAsState()

    LaunchedEffect(Unit) { viewModel.importFromHealthConnect() }

    ResultEffect<DateSelection>(DUE_DATE_KEY) { picked ->
        viewModel.setPregnancyStatus(profile.pregnancyStatus, picked.date)
    }

    LazyListScaffold(
        title = stringResource(R.string.about_you),
        scrollBehavior = appBarScrollBehavior(),
        horizontalPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!viewModel.healthConnectAvailable) {
            item { MedicalStorageNotice() }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                CardRows {
                    val pregnancyLabels = PregnancyStatus.entries.associateWith {
                        stringResource(it.labelRes())
                    }
                    SettingsExposedSelectRow(
                        label = stringResource(R.string.field_pregnancy_status),
                        selected = profile.pregnancyStatus,
                        options = PregnancyStatus.entries,
                        itemLabel = { pregnancyLabels.getValue(it) },
                        onSelect = { picked ->
                            viewModel.setPregnancyStatus(
                                picked,
                                // Clearing the status clears the date with it: an expected delivery
                                // date on a "not pregnant" record is contradictory.
                                if (picked == PregnancyStatus.Pregnant) {
                                    profile.dueDate?.toLocalDate()
                                } else {
                                    null
                                },
                            )
                        },
                    )
                    if (profile.pregnancyStatus == PregnancyStatus.Pregnant) {
                        PickerField(
                            label = stringResource(R.string.field_due_date),
                            value = profile.dueDate?.let { medicalDateString(it) }.orEmpty(),
                            placeholder = stringResource(R.string.not_recorded),
                            onClick = {
                                backStack.add(
                                    Route.MedicalDatePicker(
                                        DUE_DATE_KEY,
                                        profile.dueDate?.toLocalDate() ?: today(),
                                        allowClear = true,
                                    )
                                )
                            },
                        )
                    }

                    val smokingLabels = SmokingStatus.entries.associateWith {
                        stringResource(it.labelRes())
                    }
                    SettingsExposedSelectRow(
                        label = stringResource(R.string.field_smoking_status),
                        selected = profile.smokingStatus,
                        options = SmokingStatus.entries,
                        itemLabel = { smokingLabels.getValue(it) },
                        onSelect = { picked -> viewModel.setSmokingStatus(picked) },
                    )
                }
            }
        }

        item { SectionLabel(stringResource(R.string.section_lifestyle)) }
        item { QuestionCard(SocialHistoryQuestions.LIFESTYLE, answers, viewModel) }

        item { SectionLabel(stringResource(R.string.section_circumstances)) }
        item { QuestionCard(SocialHistoryQuestions.CIRCUMSTANCES, answers, viewModel) }
    }
}

/** A group of questions as one card of rows, each opening a dialog to answer it. */
@Composable
private fun QuestionCard(
    questions: List<SocialHistoryQuestions.Question>,
    answers: Map<String, ProfileAnswer>,
    viewModel: MedicalViewModel,
) {
    var asking by remember { mutableStateOf<SocialHistoryQuestions.Question?>(null) }

    asking?.let { question ->
        AnswerPickerDialog(
            question = question,
            selected = question.answers
                .firstOrNull { it.code == answers[question.loinc]?.answerCode },
            onPick = { picked ->
                if (picked == null) {
                    viewModel.clearSocialHistoryAnswer(question)
                } else {
                    viewModel.setSocialHistoryAnswer(question, picked)
                }
                asking = null
            },
            onDismiss = { asking = null },
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            questions.forEach { question ->
                val selected = question.answers
                    .firstOrNull { it.code == answers[question.loinc]?.answerCode }
                SettingsRow(
                    // The question goes in the title, which wraps, rather than in a field label,
                    // which would truncate it.
                    title = stringResource(question.titleRes),
                    supportingText = selected?.let { stringResource(it.labelRes) }
                        ?: stringResource(R.string.not_answered),
                    onClick = { asking = question },
                )
            }
        }
    }
}

/** The padding and spacing shared by both cards, so they line up with each other. */
@Composable
private fun CardRows(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

private fun today() =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

internal fun PregnancyStatus.labelRes() = when (this) {
    PregnancyStatus.Unknown -> R.string.not_answered
    PregnancyStatus.Pregnant -> R.string.pregnancy_pregnant
    PregnancyStatus.NotPregnant -> R.string.pregnancy_not_pregnant
}

internal fun SmokingStatus.labelRes() = when (this) {
    SmokingStatus.Unknown -> R.string.not_answered
    SmokingStatus.Never -> R.string.smoking_never
    SmokingStatus.Former -> R.string.smoking_former
    SmokingStatus.Current -> R.string.smoking_current
}
