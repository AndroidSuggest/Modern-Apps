package com.vayunmathur.maps.util
import com.vayunmathur.library.network.NetworkClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive

object Wikidata {
    // Proxied via self-hosted to avoid direct third-party (Wikidata rate-limits, privacy).
    // Server forwards to https://www.wikidata.org/w/rest.php/wikibase/v1/entities/items/{id} with 24h cache.
    private const val BASE_URL = "https://api.vayunmathur.com/api/wikidata/items"

    suspend fun get(wikidata: String): Wikidata {
        return NetworkClient.getJson("$BASE_URL/$wikidata")
    }

    @Serializable
    data class Wikidata(
        val id: String,
        val statements: Map<String, List<Statement>>,
        val sitelinks: Map<String, Sitelink>
    ) {
        fun getProperty(property: String) = statements[property]?.first()?.value?.content?.jsonPrimitive?.content
        fun getWikipedia() = sitelinks["enwiki"]?.url

        @Serializable
        data class Statement(
            val id: String,
            val value: Value,
        ) {
            @Serializable
            data class Value(val content: JsonElement? = null)
        }
        @Serializable
        data class Sitelink(
            val url: String,
        )
    }
}
