package com.vayunmathur.messages.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.messages.data.Conversation
import com.vayunmathur.messages.data.Message
import com.vayunmathur.messages.data.MessageDirection
import com.vayunmathur.messages.data.MessageSource
import com.vayunmathur.messages.data.MessageState
import com.vayunmathur.messages.util.ChatItem
import com.vayunmathur.messages.util.ConversationActions
import com.vayunmathur.messages.util.ConversationUiState
import com.vayunmathur.messages.util.InboxActions
import com.vayunmathur.messages.util.InboxRow
import com.vayunmathur.messages.util.InboxUiState

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store listing images for `:messages`, rendered from Compose previews instead of from an
 * instrumented test on a device. See `common-conventions-preview-metadata`.
 *
 * The sample threads below are hand-written [Conversation]/[Message] values. Nothing here
 * opens the encrypted database or speaks to a bridge — the screens take plain state, so the
 * previews can hand them a plausible inbox and be done.
 *
 * Things to keep in mind when editing:
 *
 *  - Order comes from the function names, which the generated PNG filenames embed. Renumber
 *    when reordering the listing.
 *  - Every clock face here is a literal string, and [ChatItem] carries them pre-formatted for
 *    exactly that reason: the app derives "Today" / "14:32" from the current time, which
 *    would give a different image on every run.
 *  - `serviceData` is left null throughout. It is the only field the row helpers parse as
 *    JSON, and null short-circuits every one of them.
 *  - `avatarUrl` is left null so the avatars fall back to initials. Layoutlib has no network,
 *    so a URL would render as an empty circle.
 *  - Each preview needs @PreviewTest as well as @Preview, and they must be members of a
 *    class. @Preview alone, or a top-level function, renders in Studio but is not collected
 *    as a screenshot test — which surfaces as "did not discover any tests".
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-inbox", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview1Inbox() {
        DynamicTheme(darkTheme = true) {
            InboxScreen(
                state = InboxUiState(
                    rows = listOf(
                        InboxRow(
                            conversation = conversation(
                                id = "sig:1",
                                source = MessageSource.SIGNAL,
                                peerName = "Priya Raman",
                                preview = "Window please 🙏",
                                unreadCount = 2,
                            ),
                            timeLabel = "07:41",
                        ),
                        InboxRow(
                            conversation = conversation(
                                id = "wa:2",
                                source = MessageSource.WHATSAPP,
                                peerName = "Trail Crew",
                                preview = "Marcus: parking at the north lot then",
                                unreadCount = 5,
                                isGroup = true,
                                participantCount = 4,
                            ),
                            timeLabel = "07:12",
                        ),
                        InboxRow(
                            conversation = conversation(
                                id = "msgs:3",
                                source = MessageSource.MESSAGES_WEB,
                                peerName = "Dad",
                                preview = "The plumber can come Thursday morning",
                                conversationType = "RCS",
                            ),
                            timeLabel = "Yesterday",
                        ),
                        InboxRow(
                            conversation = conversation(
                                id = "tg:4",
                                source = MessageSource.TELEGRAM,
                                peerName = "Noor Haddad",
                                preview = "sent you the draft, no rush",
                                unreadCount = 1,
                            ),
                            timeLabel = "Mon",
                        ),
                        InboxRow(
                            conversation = conversation(
                                id = "msgs:5",
                                source = MessageSource.MESSAGES_WEB,
                                peerName = "+1 415 555 0148",
                                preview = "Your delivery is 2 stops away",
                                conversationType = "SMS",
                            ),
                            timeLabel = "Mon",
                        ),
                        InboxRow(
                            conversation = conversation(
                                id = "ig:6",
                                source = MessageSource.INSTAGRAM,
                                peerName = "Jonas Weller",
                                preview = "hah, that's the one I was thinking of",
                            ),
                            timeLabel = "Sun",
                        ),
                        InboxRow(
                            conversation = conversation(
                                id = "voice:7",
                                source = MessageSource.VOICE,
                                peerName = "Clinic reception",
                                preview = "Appointment confirmed for the 14th",
                                conversationType = "SMS",
                            ),
                            timeLabel = "12/03/25",
                        ),
                    ),
                    connectedSources = listOf(
                        MessageSource.MESSAGES_WEB,
                        MessageSource.SIGNAL,
                        MessageSource.WHATSAPP,
                    ),
                ),
                actions = InboxActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-thread", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview2Thread() {
        val peer = conversation(
            id = "sig:1",
            source = MessageSource.SIGNAL,
            peerName = "Priya Raman",
            preview = "Window please 🙏",
            conversationType = "Signal",
        )
        DynamicTheme(darkTheme = true) {
            ConversationScreen(
                state = ConversationUiState(
                    conversation = peer,
                    // Newest first — the thread renders into a reverseLayout LazyColumn.
                    items = listOf(
                        divider("Yesterday", 1),
                        incoming("m1", "Are we still on for the 8:15 train tomorrow?", "21:04"),
                        outgoing("m2", "Yep — I'll grab the tickets tonight.", "21:07", lastInRun = false),
                        outgoing("m3", "Window or aisle?", "21:07", firstInRun = false),
                        divider("Today", 2),
                        incoming(
                            "m4",
                            "Window please 🙏",
                            "07:41",
                            lastInRun = false,
                            reactions = """[{"emoji":"👍","count":1}]""",
                        ),
                        incoming("m5", "Also the platform changed — it's 4b, not 4a.", "07:42", firstInRun = false),
                        outgoing("m6", "Good catch. Tickets are in your inbox.", "07:58"),
                    ).asReversed(),
                    canReact = true,
                ),
                actions = ConversationActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-group", device = PHONE, showSystemUi = true)
    @Composable
    fun Preview3Group() {
        val group = conversation(
            id = "wa:2",
            source = MessageSource.WHATSAPP,
            peerName = "Trail Crew",
            preview = "parking at the north lot then",
            isGroup = true,
            participantCount = 4,
        )
        DynamicTheme(darkTheme = true) {
            ConversationScreen(
                state = ConversationUiState(
                    conversation = group,
                    items = listOf(
                        divider("Saturday", 3),
                        incoming("g1", "Weather looks clear for Sunday", "18:22", sender = "Marcus"),
                        incoming("g2", "I can drive, room for three", "18:25", sender = "Ines"),
                        outgoing("g3", "Count me in. What time?", "18:31"),
                        incoming(
                            "g4",
                            "Trailhead at 7, back by 2 if we keep moving",
                            "18:40",
                            sender = "Marcus",
                            reactions = """[{"emoji":"🥾","count":3},{"emoji":"❤️","count":1}]""",
                        ),
                        divider("Today", 4),
                        incoming("g5", "Parking at the north lot then", "07:12", sender = "Ines"),
                        outgoing("g6", "Perfect, see you there.", "07:14", state = MessageState.SENT),
                    ).asReversed(),
                    canReact = true,
                ),
                actions = ConversationActions.Noop,
            )
        }
    }
}

// ---- sample-data helpers ------------------------------------------------
//
// Small builders so the previews above read as a conversation rather than as a wall of
// named arguments. Timestamps are fixed epoch-ms; only the *label* is ever drawn.

private fun conversation(
    id: String,
    source: MessageSource,
    peerName: String,
    preview: String,
    unreadCount: Int = 0,
    isGroup: Boolean = false,
    participantCount: Int = 0,
    conversationType: String? = null,
) = Conversation(
    id = id,
    source = source,
    peerName = peerName,
    peerPhoneE164 = null,
    avatarUrl = null,
    lastMessagePreview = preview,
    unreadCount = unreadCount,
    isGroup = isGroup,
    participantCount = participantCount,
    conversationType = conversationType,
)

private fun divider(label: String, dayKey: Long) = ChatItem.DayDivider(label, dayKey)

private fun incoming(
    id: String,
    body: String,
    timeLabel: String,
    sender: String? = null,
    firstInRun: Boolean = true,
    lastInRun: Boolean = true,
    reactions: String? = null,
) = ChatItem.Msg(
    message = message(id, body, MessageDirection.INCOMING, MessageState.DELIVERED, sender, reactions),
    timeLabel = timeLabel,
    showSender = firstInRun,
    isFirstInRun = firstInRun,
    isLastInRun = lastInRun,
)

private fun outgoing(
    id: String,
    body: String,
    timeLabel: String,
    firstInRun: Boolean = true,
    lastInRun: Boolean = true,
    state: MessageState = MessageState.DELIVERED,
    reactions: String? = null,
) = ChatItem.Msg(
    message = message(id, body, MessageDirection.OUTGOING, state, null, reactions),
    timeLabel = timeLabel,
    showSender = false,
    isFirstInRun = firstInRun,
    isLastInRun = lastInRun,
)

private fun message(
    id: String,
    body: String,
    direction: MessageDirection,
    state: MessageState,
    senderName: String?,
    reactionsJson: String?,
) = Message(
    id = id,
    conversationId = "preview",
    body = body,
    direction = direction,
    state = state,
    // Never drawn — the bubble uses its pre-formatted label. Fixed so nothing here is
    // derived from the clock at render time.
    timestamp = 1_700_000_000_000L,
    senderName = senderName,
    reactionsJson = reactionsJson,
)
