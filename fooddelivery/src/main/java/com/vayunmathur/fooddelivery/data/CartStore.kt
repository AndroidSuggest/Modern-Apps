package com.vayunmathur.fooddelivery.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object CartStore {

    private const val PREFS_NAME = "fooddelivery_cart"
    private const val KEY = "cart_items"

    private val json = Json { ignoreUnknownKeys = true }

    fun getAll(context: Context): List<CartItem> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<CartItem>>(raw)
        } catch (_: Exception) { emptyList() }
    }

    fun save(context: Context, items: List<CartItem>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, json.encodeToString(items))
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY)
            .apply()
    }
}
