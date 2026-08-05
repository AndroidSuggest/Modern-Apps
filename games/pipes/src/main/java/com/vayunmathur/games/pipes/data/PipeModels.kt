package com.vayunmathur.games.pipes.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class CellPos(val row: Int, val col: Int)

data class RenderPos(val x: Float, val y: Float)

data class EndpointPair(val colorIndex: Int, val cells: List<CellPos>)

data class LevelData(
    val id: String,
    val rows: Int,
    val cols: Int,
    val cells: Set<CellPos>,
    val adjacency: Map<CellPos, List<CellPos>>,
    val renderPositions: Map<CellPos, RenderPos>?,
    val endpoints: List<EndpointPair>,
    val bridges: Set<CellPos>,
    val optimalMoves: Int
)

data class LevelPack(
    val name: String,
    val shape: String,
    val levels: List<LevelData>
) {
    companion object {
        private val PACK_FILES = listOf(
            "packs/5x5.json",
            "packs/6x6.json",
            "packs/7x7.json",
            "packs/8x8.json",
            "packs/9x9.json",
            "packs/10x10.json",
            "packs/11x11.json",
            "packs/12x12.json",
            "packs/13x13.json",
            "packs/14x14.json",
            "packs/tower.json",
            "packs/hourglass.json",
            "packs/blob.json",
            "packs/inkblot.json",
            "packs/walls.json"
        )

        var PACKS: List<LevelPack> = listOf()
            private set

        fun init(context: Context) {
            PACKS = PACK_FILES.map { filename ->
                packFromJson(context.assets.open(filename).bufferedReader().readText())
            }
        }
    }
}

private fun rectangularCells(rows: Int, cols: Int): Set<CellPos> {
    return buildSet {
        for (r in 0 until rows) for (c in 0 until cols) add(CellPos(r, c))
    }
}

internal fun computeAdjacency(cells: Set<CellPos>): Map<CellPos, List<CellPos>> {
    val dirs = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
    return cells.associateWith { cell ->
        dirs.mapNotNull { (dr, dc) ->
            val neighbor = CellPos(cell.row + dr, cell.col + dc)
            if (neighbor in cells) neighbor else null
        }
    }
}

private fun packFromJson(json: String): LevelPack {
    val obj = Json.parseToJsonElement(json).jsonObject
    val name = obj["name"]!!.jsonPrimitive.content
    val shape = obj["shape"]!!.jsonPrimitive.content
    val levels = obj["levels"]!!.jsonArray.map { levelFromJson(it.jsonObject, shape) }
    return LevelPack(name, shape, levels)
}

private fun levelFromJson(json: kotlinx.serialization.json.JsonObject, shape: String): LevelData {
    val id = json["id"]!!.jsonPrimitive.content
    val rows = json["rows"]!!.jsonPrimitive.int
    val cols = json["cols"]!!.jsonPrimitive.int

    val cells = json["cells"]?.jsonArray?.map {
        val arr = it.jsonArray
        CellPos(arr[0].jsonPrimitive.int, arr[1].jsonPrimitive.int)
    }?.toSet() ?: rectangularCells(rows, cols)

    val adjacency = json["adjacency"]?.jsonObject?.map { (key, value) ->
        val parts = key.split(",")
        val cell = CellPos(parts[0].toInt(), parts[1].toInt())
        val neighbors = value.jsonArray.map {
            val arr = it.jsonArray
            CellPos(arr[0].jsonPrimitive.int, arr[1].jsonPrimitive.int)
        }
        cell to neighbors
    }?.toMap() ?: computeAdjacency(cells)

    val renderPositions = json["renderPositions"]?.jsonObject?.map { (key, value) ->
        val parts = key.split(",")
        val cell = CellPos(parts[0].toInt(), parts[1].toInt())
        val posObj = value.jsonObject
        val pos = RenderPos(posObj["x"]!!.jsonPrimitive.float, posObj["y"]!!.jsonPrimitive.float)
        cell to pos
    }?.toMap()

    val endpoints = json["endpoints"]!!.jsonArray.mapIndexed { index, it ->
        val epObj = it.jsonObject
        val colorIndex = epObj["color"]?.jsonPrimitive?.intOrNull ?: index
        val epCells = epObj["cells"]!!.jsonArray.map { c ->
            val arr = c.jsonArray
            CellPos(arr[0].jsonPrimitive.int, arr[1].jsonPrimitive.int)
        }
        EndpointPair(colorIndex, epCells)
    }

    val bridges = json["bridges"]?.jsonArray?.map {
        val arr = it.jsonArray
        CellPos(arr[0].jsonPrimitive.int, arr[1].jsonPrimitive.int)
    }?.toSet() ?: emptySet()

    val optimalMoves = json["optimalMoves"]!!.jsonPrimitive.int

    return LevelData(id, rows, cols, cells, adjacency, renderPositions, endpoints, bridges, optimalMoves)
}
