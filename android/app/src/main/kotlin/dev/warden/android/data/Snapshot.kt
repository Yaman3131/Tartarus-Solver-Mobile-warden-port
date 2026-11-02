package dev.warden.android.data

import org.json.JSONObject

/**
 * The session's state as one JSON object, pulled at ~10 Hz — the mirror of
 * `take` in `src/app/snapshot.rs`; a field is added or renamed there and
 * here together. What the editor does not need (the model's cells back,
 * `mark`, `missed`, `runOver`) is left in the raw object.
 */
data class Snapshot(
    val scanning: Boolean,
    val solving: Boolean,
    val rounds: Long,
    val rollouts: Long,
    val reading: Reading?,
    val advice: Advice?,
    val ranking: List<RankRow>,
    val mark: Mark?,
    val tools: List<String>,
    val theme: Int?,
    val themeName: String?,
    val daysLeft: Int?,
    val problems: List<String>,
) {
    data class Reading(
        val screen: String,
        val daysLeft: Int?,
        val warnings: List<Warning>,
        /** The board the reader installed, for the live board and its corrections. */
        val model: LiveModel?,
    )

    /** The board the reader installed, the mirror of `Model` in `src/board.rs`. */
    data class LiveModel(
        val day: Int,
        val warden: Int,
        val cells: List<Cell>,
    )

    data class Warning(val cell: Int, val kind: String, val text: String)

    /** Where to click for the advised move: centre and half sizes in frame px. */
    data class Mark(val x: Float, val y: Float, val w: Float, val h: Float)

    data class Advice(val label: String, val why: String, val option: Int?)

    data class RankRow(
        val rank: Int,
        val label: String,
        val mean: Double,
        val se: Double,
        val n: Long,
        val best: Boolean,
    )

    companion object {
        fun parse(text: String): Snapshot? = try {
            // The JNI export wraps the state: {"ok":true,"snapshot":{...}}.
            val o = JSONObject(text).optJSONObject("snapshot") ?: return null
            val reading = o.optJSONObject("reading")?.let { r ->
                Reading(
                    screen = r.optString("screen"),
                    daysLeft = r.optIntOrNull("daysLeft"),
                    warnings = r.optJSONArray("warnings")?.mapObjects { w ->
                        Warning(
                            cell = w.optInt("cell"),
                            kind = w.optString("kind"),
                            text = w.optString("text"),
                        )
                    } ?: emptyList(),
                    model = r.optJSONObject("model")?.let { m ->
                        LiveModel(
                            day = m.optInt("day"),
                            warden = m.optInt("warden"),
                            cells = m.optJSONArray("cells")?.mapObjects { Cell.fromJson(it) }
                                ?: emptyList(),
                        )
                    },
                )
            }
            Snapshot(
                scanning = o.optBoolean("scanning"),
                solving = o.optBoolean("solving"),
                rounds = o.optLong("rounds"),
                rollouts = o.optLong("rollouts"),
                reading = reading,
                advice = o.optJSONObject("advice")?.let { a ->
                    Advice(
                        label = a.optString("label"),
                        why = a.optString("why"),
                        option = a.optIntOrNull("option"),
                    )
                },
                mark = o.optJSONObject("mark")?.let { m ->
                    Mark(
                        x = m.optDouble("x").toFloat(),
                        y = m.optDouble("y").toFloat(),
                        w = m.optDouble("w").toFloat(),
                        h = m.optDouble("h").toFloat(),
                    )
                },
                ranking = o.optJSONArray("ranking")?.mapObjects { r ->
                    RankRow(
                        rank = r.optInt("rank"),
                        label = r.optString("label"),
                        mean = r.optDouble("mean"),
                        se = r.optDouble("se"),
                        n = r.optLong("n"),
                        best = r.optBoolean("best"),
                    )
                } ?: emptyList(),
                tools = o.optJSONArray("tools")?.let { a ->
                    List(a.length()) { a.optString(it) }
                } ?: emptyList(),
                theme = o.optIntOrNull("theme"),
                themeName = o.optStringOrNull("themeName"),
                daysLeft = o.optIntOrNull("daysLeft"),
                problems = o.optJSONArray("problems")?.let { a ->
                    List(a.length()) { a.optString(it) }
                } ?: emptyList(),
            )
        } catch (_: Exception) {
            null
        }

        private fun JSONObject.optIntOrNull(key: String): Int? =
            if (isNull(key)) null else optInt(key)

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key)

        private fun <T> org.json.JSONArray?.mapObjects(f: (JSONObject) -> T): List<T> {
            if (this == null) return emptyList()
            return List(length()) { f(getJSONObject(it)) }
        }
    }
}
