package com.vayunmathur.games.alchemist.data
import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object Alchemist {
    var items: List<AlchemyItem> = emptyList()
        private set
    var recipes: List<AlchemyRecipe> = emptyList()
        private set

    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val itemsJson = context.assets.open("items.json")
            .bufferedReader()
            .readText()
        val jsonItems = Json.decodeFromString<List<JsonItem>>(itemsJson)
        recipes = jsonItems.flatMap { item ->
            item.recipes.map { recipe ->
                AlchemyRecipe(
                    recipe,
                    listOf(item.id)
                )
            }
        }.groupBy { it.inputs }.map { (inputs, outputs) -> AlchemyRecipe(inputs,
            outputs.flatMap { it.outputs }) }
        val hasCombinations = recipes.flatMap { it.inputs }.toSet()
        items = jsonItems.map { item -> AlchemyItem(item.id, item.name, item.id !in hasCombinations) }
        initialized = true
    }

    @Serializable
    data class JsonItem(val id: Long, val name: String, val recipes: List<List<Long>>)
}

data class AlchemyItem(val id: Long, val name: String, val final: Boolean)

data class AlchemyRecipe(val inputs: List<Long>, val outputs: List<Long>)
