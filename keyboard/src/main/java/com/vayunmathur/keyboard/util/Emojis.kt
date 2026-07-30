package com.vayunmathur.keyboard.util

/** A named group of emoji shown as one tab on the emoji page. */
data class EmojiCategory(val label: String, val emojis: List<String>)

/**
 * A small, curated, fully-offline emoji set grouped into a few categories. Enough to be
 * usable; a fuller set can be dropped in later without touching the UI.
 */
object Emojis {
    val CATEGORIES: List<EmojiCategory> = listOf(
        EmojiCategory(
            "😀",
            listOf(
                "😀", "😁", "😂", "😃", "😄",
                "😅", "😆", "😉", "😊", "😋",
                "😎", "😍", "😘", "😗", "😜",
                "😝", "🤑", "🤗", "🤔", "😐",
                "🙄", "😏", "😒", "😞", "😢",
                "😭", "😫", "😠", "😡", "🥳",
                "😇", "🥰", "😴", "🤤", "😱",
            ),
        ),
        EmojiCategory(
            "👍",
            listOf(
                "👍", "👎", "👌", "✌️", "🤞",
                "👏", "🙌", "🙏", "💪", "👋",
                "✋", "🤝", "✍️", "🧠", "👀",
                "❤️", "🖤", "💔", "💕", "✨",
                "🔥", "🎉", "🎊", "⭐", "🌟",
            ),
        ),
        EmojiCategory(
            "🐶",
            listOf(
                "🐶", "🐱", "🐭", "🐹", "🐰",
                "🦊", "🐻", "🐼", "🐨", "🐯",
                "🦁", "🐷", "🐸", "🐵", "🐔",
                "🐧", "🐦", "🦆", "🦉", "🐝",
                "🦋", "🐞", "🐢", "🐟", "🐳",
            ),
        ),
        EmojiCategory(
            "🍔",
            listOf(
                "🍎", "🍌", "🍉", "🍇", "🍓",
                "🍒", "🍑", "🍊", "🍋", "🍅",
                "🍔", "🍟", "🍕", "🌭", "🌮",
                "🍜", "🍣", "🍩", "🍰", "☕",
                "🍺", "🍷", "🍹", "🥤", "🍦",
            ),
        ),
        EmojiCategory(
            "⚙️",
            listOf(
                "⚙️", "📱", "💻", "⌚", "📷",
                "🔋", "💡", "🔦", "🔑", "🔒",
                "📅", "✉️", "📎", "✂️", "📌",
                "🔍", "✅", "❌", "➕", "➖",
                "❗", "❓", "❤️", "💰", "🎯",
            ),
        ),
    )
}
