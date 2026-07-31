package com.vayunmathur.education.ui
import androidx.compose.ui.res.pluralStringResource
import com.vayunmathur.education.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.education.Route
import com.vayunmathur.education.content.ModuleType
import com.vayunmathur.education.util.EducationViewModel
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScholarCoursePage(backStack: NavBackStack<Route>, viewModel: EducationViewModel, courseId: String) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val content = viewModel.content
    val course = content.course(courseId)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(course?.title ?: stringResource(R.string.course)) },
                navigationIcon = { IconNavigation(backStack) },
            )
        },
    ) { padding ->
        if (course == null) {
            MissingContent(padding, stringResource(R.string.this_course_is_unavailable))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            if (course.description.isNotBlank()) {
                item {
                    Text(
                        course.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(course.units, key = { it.id }) { unit ->
                val skills = content.skillIdsOfUnit(unit)
                val deadline = viewModel.deadlineFor(ModuleType.UNIT, unit.id)
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { backStack.add(Route.UnitScreen(unit.id)) },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(unit.title, style = MaterialTheme.typography.titleMedium)
                            StarRow(averageStars(skills, progress))
                        }
                        Text(
                            pluralStringResource(R.plurals.lessons, unit.lessons.size, unit.lessons.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        deadline?.let {
                            Row(Modifier.padding(top = 8.dp)) { DeadlineChip(it.dueEpochDay) }
                        }
                    }
                }
            }
            course.challenge?.let { challenge ->
                item {
                    FilledTonalButton(
                        onClick = { backStack.add(Route.Quiz(challenge.id)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    ) {
                        Text(challenge.title.ifBlank { stringResource(R.string.course_challenge) })
                    }
                }
            }
        }
    }
}

@Composable
fun MissingContent(padding: androidx.compose.foundation.layout.PaddingValues, message: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
