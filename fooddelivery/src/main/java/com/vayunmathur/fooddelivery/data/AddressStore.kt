package com.vayunmathur.fooddelivery.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object AddressStore {

    private const val PREFS_NAME = "fooddelivery_addresses"
    private const val KEY = "saved_addresses"

    private val json = Json { ignoreUnknownKeys = true }

    fun getAll(context: Context): List<SavedAddress> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<SavedAddress>>(raw)
        } catch (_: Exception) { emptyList() }
    }

    fun save(context: Context, address: SavedAddress) {
        val list = getAll(context).toMutableList()
        val idx = list.indexOfFirst { it.id == address.id }
        val toSave = if (address.isDefault) {
            list.map { it.copy(isDefault = false) }.toMutableList()
        } else list
        if (idx >= 0) toSave[idx] = address else toSave.add(address)
        write(context, toSave)
    }

    fun delete(context: Context, id: String) {
        write(context, getAll(context).filter { it.id != id })
    }

    fun setDefault(context: Context, id: String) {
        write(context, getAll(context).map { it.copy(isDefault = it.id == id) })
    }

    fun getDefault(context: Context): SavedAddress? {
        return getAll(context).firstOrNull { it.isDefault } ?: getAll(context).firstOrNull()
    }

    private fun write(context: Context, list: List<SavedAddress>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY, json.encodeToString(list)) }
    }
}
