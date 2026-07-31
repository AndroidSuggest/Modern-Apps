package org.schabi.newpipe.extractor.services.youtube.extractors

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.services.youtube.YoutubeService
import org.schabi.newpipe.extractor.subscription.SubscriptionExtractor
import org.schabi.newpipe.extractor.subscription.SubscriptionItem
import org.schabi.newpipe.extractor.utils.JsonUtils
import org.schabi.newpipe.extractor.utils.getArray
import org.schabi.newpipe.extractor.utils.getObject
import org.schabi.newpipe.extractor.utils.getString
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.UncheckedIOException
import java.util.ArrayList
import java.util.Collections
import java.util.Objects
import java.util.stream.Collectors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Extract subscriptions from a Google takeout export
 */
class YoutubeSubscriptionExtractor(youtubeService: YoutubeService) :
    SubscriptionExtractor(youtubeService, Collections.singletonList(ContentSource.INPUT_STREAM)) {

    companion object {
        private const val BASE_CHANNEL_URL = "https://www.youtube.com/channel/"
    }

    override fun getRelatedUrl(): String = "https://takeout.google.com/takeout/custom/youtube"

    @Throws(ExtractionException::class)
    override fun fromInputStream(contentInputStream: InputStream): List<SubscriptionItem> {
        return fromJsonInputStream(contentInputStream)
    }

    @Throws(ExtractionException::class)
    override fun fromInputStream(
        contentInputStream: InputStream,
        contentType: String
    ): List<SubscriptionItem> {
        return when (contentType) {
            "json", "application/json" -> fromJsonInputStream(contentInputStream)
            "csv", "text/csv", "text/comma-separated-values" -> fromCsvInputStream(contentInputStream)
            "zip", "application/zip" -> fromZipInputStream(contentInputStream)
            else -> throw InvalidSourceException("Unsupported content type: $contentType")
        }
    }

    @Throws(ExtractionException::class)
    fun fromJsonInputStream(contentInputStream: InputStream): List<SubscriptionItem> {
        val subscriptions: JsonArray
        try {
            subscriptions = JsonUtils.toJsonArray(
                contentInputStream.bufferedReader().readText()
            )
        } catch (e: Exception) {
            throw InvalidSourceException("Invalid json input stream", e)
        }

        var foundInvalidSubscription = false
        val subscriptionItems = ArrayList<SubscriptionItem>()
        for (subscriptionObject in subscriptions) {
            if (subscriptionObject !is JsonObject) {
                foundInvalidSubscription = true
                continue
            }

            val snippet = subscriptionObject.getObject("snippet")
            val id = snippet?.getObject("resourceId")?.getString("channelId", "") ?: ""
            if (id.length != 24) {
                foundInvalidSubscription = true
                continue
            }

            subscriptionItems.add(
                SubscriptionItem(
                    service.serviceId,
                    BASE_CHANNEL_URL + id,
                    snippet.getString("title", "") ?: ""
                )
            )
        }

        if (foundInvalidSubscription && subscriptionItems.isEmpty()) {
            throw InvalidSourceException("Found only invalid channel ids")
        }
        return subscriptionItems
    }

    @Throws(ExtractionException::class)
    fun fromZipInputStream(contentInputStream: InputStream): List<SubscriptionItem> {
        try {
            ZipInputStream(contentInputStream).use { zipInputStream ->
                var zipEntry: ZipEntry?
                while (zipInputStream.nextEntry.also { zipEntry = it } != null) {
                    if (zipEntry!!.name.lowercase().endsWith(".csv")) {
                        try {
                            val csvItems = fromCsvInputStream(zipInputStream)
                            if (csvItems.isNotEmpty()) {
                                return csvItems
                            }
                        } catch (e: ExtractionException) {
                            // Ignore error and go to next file
                        }
                    }
                }
            }
        } catch (e: IOException) {
            throw InvalidSourceException("Error reading contents of zip file", e)
        }

        throw InvalidSourceException(
            "Unable to find a valid subscriptions.csv file (try extracting and selecting the csv file)"
        )
    }

    @Throws(ExtractionException::class)
    fun fromCsvInputStream(contentInputStream: InputStream): List<SubscriptionItem> {
        try {
            BufferedReader(InputStreamReader(contentInputStream)).use { reader ->
                return reader.lines()
                    .skip(1)
                    .map { line -> line.split(",").toTypedArray() }
                    .filter { values -> values.size >= 3 }
                    .map { values ->
                        val channelUrl = values[1].replace("http://", "https://")
                        if (channelUrl.startsWith(BASE_CHANNEL_URL)) {
                            SubscriptionItem(
                                service.serviceId,
                                channelUrl,
                                values[2]
                            )
                        } else null
                    }
                    .filter(Objects::nonNull)
                    .map { it!! }
                    .collect(Collectors.toUnmodifiableList())
            }
        } catch (e: UncheckedIOException) {
            throw InvalidSourceException("Error reading CSV file", e)
        } catch (e: IOException) {
            throw InvalidSourceException("Error reading CSV file", e)
        }
    }
}
