package com.vayunmathur.fooddelivery

import com.vayunmathur.fooddelivery.data.ApiResponse
import com.vayunmathur.fooddelivery.data.Brand
import com.vayunmathur.fooddelivery.data.Merchant
import com.vayunmathur.fooddelivery.data.MerchantItem
import com.vayunmathur.fooddelivery.data.MerchantsWrapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class BitesApiTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun httpGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 30000
        conn.readTimeout = 60000
        return conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
    }

    @Test
    fun testRawResponse() {
        val raw = httpGet("https://api.deliverycollective.com/api/v1/merchants/all/stores")
        println("RAW response length: ${raw.length}")
        println("RAW first 500 chars: ${raw.take(500)}")
        assert(raw.isNotEmpty()) { "Response was empty" }
    }

    @Test
    fun testParseSingleMerchant() {
        val singleMerchantJson = """
        {
            "id": 2615,
            "name": "Test Restaurant",
            "addressStreet": "123 Main St",
            "addressCity": "San Mateo",
            "addressState": "CA",
            "addressZip": "94401",
            "latitude": 37.5,
            "longitude": -122.3,
            "logoUrl": "https://example.com/logo.jpg",
            "imageUrl": "https://example.com/image.jpg",
            "isOpen": true,
            "isDeliveryEnabled": true,
            "isPickupEnabled": true,
            "closingTime": "Closes at 8:00PM",
            "nextOpenWindow": "ASAP",
            "averageRating": null,
            "totalRatings": null,
            "rewardsPercentage": null,
            "brandColor": null,
            "brand": null,
            "merchantTags": ["Pizza"],
            "items": [],
            "sortOrder": null,
            "distance": null,
            "freeDeliveryThreshold": 1000,
            "doordashMarkup": null,
            "doordashMarkupComparison": null,
            "storefrontAlias": "test",
            "extraFieldThatShouldBeIgnored": "hello"
        }
        """.trimIndent()

        val merchant = json.decodeFromString<Merchant>(singleMerchantJson)
        println("Parsed merchant: ${merchant.name}, id=${merchant.id}")
        assert(merchant.name == "Test Restaurant")
        assert(merchant.averageRating == null)
        assert(merchant.brandColor == null)
    }

    @Test
    fun testParseWrappedResponse() {
        val wrappedJson = """
        {
            "message": "success",
            "data": {
                "merchants": [
                    {
                        "id": 1,
                        "name": "Restaurant A",
                        "addressStreet": "",
                        "addressCity": "",
                        "addressState": "",
                        "addressZip": "",
                        "latitude": 0.0,
                        "longitude": 0.0,
                        "logoUrl": "",
                        "imageUrl": "",
                        "isOpen": true,
                        "closingTime": "",
                        "nextOpenWindow": "",
                        "merchantTags": [],
                        "items": [],
                        "freeDeliveryThreshold": 0,
                        "averageRating": 4.5,
                        "totalRatings": 100,
                        "rewardsPercentage": 5.0,
                        "brandColor": "#ff0000"
                    }
                ],
                "total": 1,
                "tags": ["Pizza"]
            }
        }
        """.trimIndent()

        val resp = json.decodeFromString<ApiResponse<MerchantsWrapper>>(wrappedJson)
        println("Parsed wrapper: message=${resp.message}, merchants=${resp.data?.merchants?.size}")
        assert(resp.data?.merchants?.size == 1)
        assert(resp.data?.merchants?.first()?.name == "Restaurant A")
    }

    @Test
    fun testParseRealApiResponse() {
        val raw = httpGet("https://api.deliverycollective.com/api/v1/merchants/all/stores")
        println("Got ${raw.length} bytes from API")

        try {
            val resp = json.decodeFromString<ApiResponse<MerchantsWrapper>>(raw)
            val merchants = resp.data?.merchants ?: emptyList()
            println("SUCCESS: Parsed ${merchants.size} merchants")
            merchants.take(3).forEach { m ->
                println("  - ${m.name} (id=${m.id}, rating=${m.averageRating}, tags=${m.merchantTags})")
            }
            assert(merchants.isNotEmpty()) { "No merchants parsed!" }
        } catch (e: Exception) {
            println("PARSE FAILED: ${e::class.simpleName}: ${e.message}")
            e.printStackTrace()
            // Try to parse just the first merchant to isolate the issue
            try {
                val jsonElement = json.parseToJsonElement(raw)
                val dataObj = jsonElement.jsonObject["data"]!!.jsonObject
                val merchantsArr = dataObj["merchants"]!!.jsonArray
                println("JSON has ${merchantsArr.size} merchants in array")

                // Try parsing one by one to find the bad one
                for (i in 0 until merchantsArr.size) {
                    try {
                        json.decodeFromString<Merchant>(merchantsArr[i].toString())
                    } catch (e2: Exception) {
                        println("MERCHANT[$i] FAILED: ${e2.message?.take(200)}")
                        println("  JSON: ${merchantsArr[i].toString().take(300)}")
                        break
                    }
                }
            } catch (e3: Exception) {
                println("Even basic JSON parse failed: ${e3.message}")
            }
            throw e
        }
    }
}
