package com.vayunmathur.email.network.imap

import com.vayunmathur.email.data.Attachment

internal class BodyCollector(
        private val uid: Long,
        private val accountEmail: String,
        private val folderName: String,
    ) {
        var body: String? = null
        var isHtml = false
        val attachments = mutableListOf<Attachment>()

        fun walk(part: MimeParser.ParsedPart) {
            if (part.contentType.isMultipart) {
                part.children.forEach { walkChild(it) }
            } else {
                absorb(extractSingle(part))
            }
        }

        private fun walkChild(child: MimeParser.ParsedPart) {
            if (child.contentType.isMultipart) {
                walk(child)
                return
            }
            absorb(extractSingle(child))
        }

        private fun absorb(triple: Triple<String?, Boolean, List<Attachment>>) {
            val (b, h, a) = triple
            attachments.addAll(a)
            if (b == null) return
            if (body == null || (h && !isHtml)) {
                body = b
                isHtml = h
            }
        }

        private fun extractSingle(part: MimeParser.ParsedPart): Triple<String?, Boolean, List<Attachment>> {
            val ct = part.contentType
            if (isInlineImage(part)) return Triple(null, false, emptyList())
            val filename = part.disposition?.filename ?: ct.params["name"]
            if (!filename.isNullOrBlank() || part.disposition?.isAttachment == true) {
                return Triple(null, false, listOf(toAttachment(part, filename ?: "unnamed")))
            }
            if (ct.isText) {
                return Triple(part.bodyText, ct.subType.equals("html", true), emptyList())
            }
            return Triple(null, false, emptyList())
        }

        private fun isInlineImage(part: MimeParser.ParsedPart): Boolean {
            val ct = part.contentType
            val cid = part.contentId ?: return false
            return ct.isImage || part.disposition?.isInline == true || ct.fullType.startsWith("image/", true)
        }

        private fun toAttachment(part: MimeParser.ParsedPart, filename: String): Attachment {
            val fn = MimeParser.sanitizeFilename(filename)
            val mime = part.contentType.fullType
            return Attachment(
                accountEmail,
                folderName,
                uid,
                part.partId,
                fn,
                mime,
                part.decodedBytes.size.toLong(),
            )
        }
    }
