package com.vayunmathur.code.util

import androidx.lifecycle.viewModelScope
import com.vayunmathur.code.syntax.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

internal const val MAX_SEARCH_RESULTS = 500
internal const val MAX_MATCHES_PER_FILE = 50
internal const val MAX_SEARCH_FILE_SIZE = 500_000
internal const val MAX_PROJECT_FILES = 5000
internal const val MAX_RECENT_FILES = 15
internal val SKIP_DIRS = setOf(".git", "node_modules", "build", ".gradle", ".idea")

internal fun EditorViewModel.searchProjectImpl(query: String, caseSensitive: Boolean, useRegex: Boolean) {
    val root = rootDir ?: return
    searchJob?.cancel()
    if (query.isBlank()) {
        searchResults.clear()
        isSearching = false
        return
    }
    isSearching = true
    searchResults.clear()
    searchJob = viewModelScope.launch {
        val collected = withContext(Dispatchers.IO) {
            val out = ArrayList<SearchResult>()
            val ignore = loadGitIgnore(root)
            val stack = ArrayDeque<File>()
            stack.addLast(root)
            while (stack.isNotEmpty() && out.size < MAX_SEARCH_RESULTS) {
                coroutineContext.ensureActive()
                val dir = stack.removeLast()
                val children = runCatching { FileFiles.listChildren(dir) }.getOrDefault(emptyList())
                for (child in children) {
                    if (out.size >= MAX_SEARCH_RESULTS) break
                    val rel = relativeTo(root, child.file)
                    if (child.isDirectory) {
                        if (child.name !in SKIP_DIRS && !ignore.isIgnored(rel, true)) stack.addLast(child.file)
                        continue
                    }
                    if (ignore.isIgnored(rel, false)) continue
                    if (Language.fromFileName(child.name) == Language.PLAINTEXT) continue
                    if (child.file.length() > MAX_SEARCH_FILE_SIZE) continue
                    val text = runCatching { FileFiles.readText(child.file) }.getOrNull() ?: continue
                    val matches = findLineMatches(text, query, caseSensitive, useRegex, MAX_MATCHES_PER_FILE)
                    for (m in matches) {
                        out.add(SearchResult(child.file.absolutePath, child.name, m.line, m.preview))
                        if (out.size >= MAX_SEARCH_RESULTS) break
                    }
                }
            }
            out
        }
        searchResults.clear()
        searchResults.addAll(collected)
        isSearching = false
    }
}

internal fun EditorViewModel.openSearchResultImpl(result: SearchResult) {
    addRecentFile(result.path)
    val existing = tabs.indexOfFirst { it.key == result.path }
    if (existing >= 0) {
        currentIndex = existing
        goToLine(result.line)
        return
    }
    val file = File(result.path)
    viewModelScope.launch {
        tabs.add(makeFileTab(file, result.name))
        currentIndex = tabs.lastIndex
        goToLine(result.line)
        saveSession()
    }
}

/** Rebuilds the cached project-file list by walking the open folder off the main thread. */
internal fun EditorViewModel.refreshProjectFilesImpl() {
    val root = rootDir ?: return
    projectFilesJob?.cancel()
    projectFilesJob = viewModelScope.launch {
        val collected = withContext(Dispatchers.IO) {
            val out = ArrayList<ProjectFileEntry>()
            val ignore = loadGitIgnore(root)
            val stack = ArrayDeque<File>()
            stack.addLast(root)
            while (stack.isNotEmpty() && out.size < MAX_PROJECT_FILES) {
                coroutineContext.ensureActive()
                val dir = stack.removeLast()
                val children = runCatching { FileFiles.listChildren(dir) }.getOrDefault(emptyList())
                for (child in children) {
                    if (out.size >= MAX_PROJECT_FILES) break
                    val rel = relativeTo(root, child.file)
                    if (child.isDirectory) {
                        if (child.name !in SKIP_DIRS && !ignore.isIgnored(rel, true)) stack.addLast(child.file)
                        continue
                    }
                    if (ignore.isIgnored(rel, false)) continue
                    out.add(toProjectEntry(child.file))
                }
            }
            out.sortedBy { it.relativePath.lowercase() }
        }
        projectFiles.clear()
        projectFiles.addAll(collected)
    }
}

/** Reads and parses the project root `.gitignore`, or returns an empty matcher. */
internal fun loadGitIgnore(root: File): GitIgnore = runCatching {
    val f = File(root, ".gitignore")
    if (f.isFile) parseGitIgnore(f.readText()) else GitIgnore.EMPTY
}.getOrDefault(GitIgnore.EMPTY)

/** Path of [file] relative to [root] (falling back to the bare name when not under root). */
internal fun relativeTo(root: File, file: File): String {
    val rp = root.absolutePath
    val ap = file.absolutePath
    return if (ap.startsWith(rp + File.separator)) ap.substring(rp.length + 1) else file.name
}

/** Builds the display entry for [file], with a path relative to the open root when possible. */
internal fun EditorViewModel.toProjectEntry(file: File): ProjectFileEntry {
    val abs = file.absolutePath
    val rootPath = rootDir?.absolutePath
    val rel = if (rootPath != null && abs.startsWith(rootPath + File.separator)) {
        abs.substring(rootPath.length + 1)
    } else {
        abs
    }
    return ProjectFileEntry(abs, file.name, rel)
}

/** Records [path] as the most-recently-opened file and persists the capped list. */
internal fun EditorViewModel.addRecentFile(path: String) {
    recentPaths.remove(path)
    recentPaths.add(0, path)
    while (recentPaths.size > MAX_RECENT_FILES) recentPaths.removeAt(recentPaths.lastIndex)
    viewModelScope.launch { prefs.setRecentFiles(recentPaths.toList()) }
}
