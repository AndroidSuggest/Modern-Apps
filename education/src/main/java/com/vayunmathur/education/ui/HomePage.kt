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
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedCard
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
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.IconEmojiEvents
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.util.NavBackStack
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScholarHomePage(backStack: NavBackStack<Route>, viewModel: EducationViewModel) {
    val learner by viewModel.learner.collectAsStateWithLifecycle()
    val deadlines by viewModel.deadlines.collectAsStateWithLifecycle()
    val l = learner ?: return
    val content = viewModel.content

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (l.name.isBlank()) stringResource(R.string.learn) else stringResource(R.string.hi_1, l.name)) },
                actions = {
                    IconButton(onClick = { backStack.add(Route.Badges) }) {
                        IconEmojiEvents()
                    }
                    IconButton(onClick = { openParentArea(backStack, viewModel) }) {
                        IconSettings()
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StreakChip(l.streakCount)
                    StarsChip(l.totalStars)
                }
            }

            if (deadlines.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.due_soon)) }
                items(deadlines, key = { it.id }) { d ->
                    val type = runCatching { ModuleType.valueOf(d.moduleType) }.getOrNull()
                    val title = type?.let { content.moduleTitle(it, d.moduleId) } ?: "Assignment"
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable { navigateToModule(backStack, type, d.moduleId) },
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            DeadlineChip(d.dueEpochDay)
                        }
                    }
                }
            }

            content.subjects.forEach { subject ->
                item { SectionHeader(stringResource(subject.displayNameRes)) }
                items(content.coursesForSubject(subject), key = { it.id }) { course ->
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable { backStack.add(Route.Course(course.id)) },
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(course.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    pluralStringResource(R.plurals.units, course.units.size, course.units.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconChevronRight()
                        }
                    }
                }
            }

            if (content.courses.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.no_content_packs_are_installed_yet),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Navigates to the screen for a module referenced by a deadline. */
fun navigateToModule(backStack: NavBackStack<Route>, type: ModuleType?, moduleId: String) {
    when (type) {
        ModuleType.COURSE -> backStack.add(Route.Course(moduleId))
        ModuleType.UNIT -> backStack.add(Route.UnitScreen(moduleId))
        ModuleType.LESSON -> backStack.add(Route.LessonScreen(moduleId))
        null -> {}
    }
}

/**
 * Opens the parent area. Until a PIN has been set, this goes straight to the
 * parent settings (where the PIN can be created); once a PIN exists the entry
 * is protected by the PIN gate.
 */
fun openParentArea(backStack: NavBackStack<Route>, viewModel: EducationViewModel) {
    val route = if (viewModel.learner.value?.pinHash == null) Route.Parent else Route.ParentGate
    backStack.add(route)
}
