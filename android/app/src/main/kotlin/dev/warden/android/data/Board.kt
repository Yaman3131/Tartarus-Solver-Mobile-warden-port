package dev.warden.android.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One cell as the player sees it — the Kotlin mirror of the analyzer's
 * `CellModel` (`src/board.rs`): serialized with a `"kind"` tag, the field
 * names as they are there. Unknown kinds read back as [Cell.Sealed].
 */
sealed class Cell {
    abstract fun toJson(): JSONObject

    /** The Interrogation Room, model index 0 — fixed. */
    data object Office : Cell() {
        override fun toJson() = JSONObject().put("kind", "office")
    }

    data class Sealed(val event: String? = null) : Cell() {
        override fun toJson() =
            JSONObject().put("kind", "sealed").put("event", event)
    }

    data class Empty(val event: String? = null) : Cell() {
        override fun toJson() =
            JSONObject().put("kind", "empty").put("event", event)
    }

    data class Inmate(
        val beast: String = "lion",
        val tier: Int = 1,
        val ivory: Boolean = false,
        val event: String? = null,
    ) : Cell() {
        override fun toJson() = JSONObject()
            .put("kind", "inmate")
            .put("beast", beast)
            .put("tier", tier)
            .put("ivory", ivory)
            .put("prey", JSONObject.NULL)
            .put("event", event)
    }

    /** `due`: null = due tomorrow, 0 = today, n = n days later. */
    data class Nest(val due: Int? = null) : Cell() {
        override fun toJson() =
            JSONObject().put("kind", "nest").put("due", due)
    }

    data class Scouted(
        val beast: String = "lion",
        val tier: Int = 1,
        val event: String? = null,
    ) : Cell() {
        override fun toJson() = JSONObject()
            .put("kind", "scouted")
            .put("beast", beast)
            .put("tier", tier)
            .put("event", event)
    }

    data class Scratch(val tier: Int = 1, val event: String? = null) : Cell() {
        override fun toJson() = JSONObject()
            .put("kind", "scratch")
            .put("tier", tier)
            .put("event", event)
    }

    companion object {
        fun fromJson(o: JSONObject): Cell = when (o.optString("kind")) {
            "office" -> Office
            "empty" -> Empty(eventMark(o))
            "inmate" -> Inmate(
                beast = o.optString("beast", "lion"),
                tier = o.optInt("tier", 1),
                ivory = o.optBoolean("ivory", false),
                event = eventMark(o),
            )
            "nest" -> Nest(if (o.isNull("due")) null else o.optInt("due"))
            "scouted" -> Scouted(
                beast = o.optString("beast", "lion"),
                tier = o.optInt("tier", 1),
                event = eventMark(o),
            )
            "scratch" -> Scratch(tier = o.optInt("tier", 1), event = eventMark(o))
            else -> Sealed(eventMark(o))
        }

        private fun eventMark(o: JSONObject) =
            if (o.isNull("event")) null else o.optString("event")
    }
}

/**
 * The hand-built board the editor holds. Its [toJson] is the analyzer's
 * Model JSON: the reading is installed from it
 * (`WardenNative.wardenSessionSetBoard`), with the fields the editor
 * leaves out defaulted by `normalize` (`src/board.rs`). The `step` is
 * always "manage" — a hand board has no destination cards to mark.
 */
data class BoardState(
    val day: Int = 1,
    val warden: Int = 0,
    val theme: Int? = null,
    val cells: List<Cell> = defaultCells(),
    val tools: Set<String> = emptySet(),
    val vault: Set<String> = emptySet(),
) {
    fun toJson(): String = JSONObject()
        .put("day", day)
        .put("maxDays", RUN_DAYS)
        .put("step", "manage")
        .put("warden", warden)
        .put("tools", JSONArray(tools))
        .put("vault", JSONArray(vault))
        .put("cells", JSONArray(cells.map { it.toJson() }))
        .toString()

    companion object {
        /** The game's run length (`board.days` in the analyzer's data). */
        const val RUN_DAYS = 20

        /** Model index 0 is the Interrogation Room; cells 02–16 are 1–15. */
        fun defaultCells(): List<Cell> =
            List(16) { i -> if (i == 0) Cell.Office else Cell.Sealed() }

        /** The cell's name on the game board: "Room" or "02"…"16". */
        fun cellLabel(index: Int): String =
            if (index == 0) "Room" else "%02d".format(index + 1)

        val BEASTS = listOf("mammoth", "lion", "wolf", "rat")

        val EVENTS = listOf("execution", "chain", "unknown")

        /** The instrument keys, as the analyzer names them (`Tool::key`). */
        val TOOL_KEYS = listOf(
            "muzzle", "chains", "collar", "revenge", "beastcage",
            "whip", "saw", "blacklist", "keys", "coin", "fire",
            "dogtag", "forgedkey", "hook", "scapegoat", "axe", "maiden",
            "scales", "rope", "stake", "pestle", "shortchain", "longchain",
            "tongs", "maul", "trap", "crown", "claws", "guillotine",
            "ironbox", "skewer",
        )

        /** The run themes, ids of the game data's `themes.relics`. */
        val THEMES = listOf(
            null to "No theme",
            301 to "Tyranny",
            302 to "Opulence",
            303 to "Iron Law",
            304 to "Cage Fight",
        )
    }
}
