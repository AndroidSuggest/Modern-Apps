package com.vayunmathur.code.util

internal const val TERMINAL_SCROLLBACK = 2000

/** Starts a shell in the open project directory if one isn't already running. */
fun EditorViewModel.startTerminal() {
    if (terminal != null) return
    val dir = rootDir ?: return
    terminal = TerminalSession(
        dir = dir,
        onLine = { line -> appendTerminalLine(line) },
        onExit = { terminalRunning = false },
    )
    terminalRunning = true
}

fun EditorViewModel.terminalSend(command: String) {
    if (terminal == null) startTerminal()
    appendTerminalLine("$ $command")
    terminal?.send(command)
}

/** Line-based shells can't deliver a real SIGINT, so "stop" kills and restarts the shell. */
fun EditorViewModel.terminalInterrupt() {
    terminal?.close()
    terminal = null
    terminalRunning = false
    appendTerminalLine("^C")
    startTerminal()
}

fun EditorViewModel.clearTerminal() {
    terminalLines.clear()
}

internal fun EditorViewModel.appendTerminalLine(line: String) {
    terminalLines.add(line)
    while (terminalLines.size > TERMINAL_SCROLLBACK) terminalLines.removeAt(0)
}
