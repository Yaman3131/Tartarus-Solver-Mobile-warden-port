package dev.warden.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.warden.android.data.Snapshot
import dev.warden.android.data.BoardState.Companion.cellLabel

/**
 * What the session says: the advice on show, the ranking behind it, the
 * board's warnings to check and the problems that keep an advice off.
 * Empty while the solver runs its first rollouts.
 */
@Composable
fun AdvicePanel(snapshot: Snapshot?, boardError: String?) {
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        boardError?.let { Problem(it) }

        snapshot?.let { s ->
            if (s.scanning) Text("Reading the frame…", color = Color(0xFF8A8A96))
            if (s.solving) {
                Text(
                    "Solving… round ${s.rounds} of ${s.rollouts}",
                    color = Color(0xFF8A8A96),
                )
            }

            s.problems.forEach { Problem(it) }

            s.advice?.let { a ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(a.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(a.why, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            s.reading?.let { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(r.screen, style = MaterialTheme.typography.titleSmall)
                    r.daysLeft?.let { Text("$it days left", style = MaterialTheme.typography.titleSmall) }
                    s.themeName?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                }
                r.warnings.forEach { w ->
                    Surface(
                        color = Color(0xFF3A3220),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Check ${cellLabel(w.cell)}: ${w.text}",
                            Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFE0C860),
                        )
                    }
                }
            }

            if (s.ranking.isNotEmpty()) {
                Text("Ranking", style = MaterialTheme.typography.titleSmall)
                s.ranking.forEach { row ->
                    val advised = s.advice?.option != null && s.advice.option == row.rank - 1
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "${row.rank}. ${row.label}",
                            fontWeight = if (advised) FontWeight.Bold else FontWeight.Normal,
                            color = if (advised) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        )
                        Text(
                            if (row.n > 0) "%.1f ±%.1f  (%d)".format(row.mean, row.se, row.n) else "—",
                            color = Color(0xFF8A8A96),
                        )
                    }
                }
            }
        } ?: Text("Starting…", color = Color(0xFF8A8A96))
    }
}

@Composable
private fun Problem(text: String) {
    Surface(
        color = Color(0xFF3A2020),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            Modifier.padding(8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFE08080),
        )
    }
}
