package com.vayunmathur.email.imap

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Office 365 / Exchange reports the inbox as "Inbox" while Gmail and friends report
 * "INBOX". Everything downstream (notably the unified inbox query) matches on the
 * literal "INBOX" with case-sensitive SQLite comparison, so folding has to happen
 * before folder names are persisted.
 */
class ImapClientMailboxTest {

    @Test
    fun `folds any casing of the inbox onto the canonical name`() {
        assertEquals("INBOX", ImapClient.canonicalizeMailbox("INBOX"))
        assertEquals("INBOX", ImapClient.canonicalizeMailbox("Inbox"))
        assertEquals("INBOX", ImapClient.canonicalizeMailbox("inbox"))
        assertEquals("INBOX", ImapClient.canonicalizeMailbox("iNbOx"))
    }

    @Test
    fun `folds the leading segment of inbox sub-folders`() {
        assertEquals("INBOX/Receipts", ImapClient.canonicalizeMailbox("Inbox/Receipts"))
        assertEquals("INBOX.Receipts", ImapClient.canonicalizeMailbox("Inbox.Receipts"))
        assertEquals("INBOX/Receipts/2024", ImapClient.canonicalizeMailbox("inbox/Receipts/2024"))
    }

    @Test
    fun `honours an explicit hierarchy delimiter`() {
        assertEquals("INBOX/Receipts", ImapClient.canonicalizeMailbox("Inbox/Receipts", "/"))
        // A dot is not the delimiter here, so "Inbox.Receipts" is an unrelated mailbox.
        assertEquals("Inbox.Receipts", ImapClient.canonicalizeMailbox("Inbox.Receipts", "/"))
    }

    @Test
    fun `leaves non-inbox mailboxes untouched`() {
        assertEquals("Sent", ImapClient.canonicalizeMailbox("Sent"))
        assertEquals("Deleted Items", ImapClient.canonicalizeMailbox("Deleted Items"))
        assertEquals("[Gmail]/All Mail", ImapClient.canonicalizeMailbox("[Gmail]/All Mail"))
        // Only the inbox is case-insensitive — a sibling that merely starts with the
        // same letters must not be rewritten.
        assertEquals("Inboxes", ImapClient.canonicalizeMailbox("Inboxes"))
        assertEquals("Inbox Archive", ImapClient.canonicalizeMailbox("Inbox Archive"))
    }
}
