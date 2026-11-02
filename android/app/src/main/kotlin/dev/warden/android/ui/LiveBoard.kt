package dev.warden.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.warden.android.data.BoardState
import dev.warden.android.data.BoardState.Companion.cellLabel
import dev.warden.android.data.Cell
import dev.warden.android.data.Snapshot

/** The tiers a cell holds: Normal to Ultimate (Chromatics leave for the vault). */
private val TIERS = listOf(
    "Normal" to Color(0xFFC8C8C8),
    "Magic" to Color(0xFF508CFF),
    "Rare" to Color(0xFFB464FF),
    "Legendary" to Color(0xFFFFAA28),
    "Ultimate" to Color(0xFFF0463C),
)

/**
 * The board the reader installed, as the game shows it: tap a cell to correct
 * what the reader got wrong (locked, open, or an inmate's beast and tier) or
 * to add or remove its incident marker — the desktop's cell window, on the
 * phone. A correction re-solves the board; the next frame read starts fresh.
 */
@Composable
fun LiveBoard(
    model: Snapshot.LiveModel,
    warnings: List<Snapshot.Warning>,
    onEdit: (cell: Int, kind: String, beast: String?, tier: Int?) -> Unit,
    onMarker: (cell: Int, on: Boolean) -> Unit,
) {
    var editing by remember { mutableIntStateOf(-1) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Day ${model.day}", style = MaterialTheme.typography.titleSmall)
            Text("Warden ${cellLabel(model.warden)}", style = MaterialTheme.typography.titleSmall)
            Text(
                "Tap a cell to correct it",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8A8A96),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(model.cells.size) { i ->
                LiveCellTile(
                    index = i,
                    cell = model.cells[i],
                    wardenHere = model.warden == i,
                    warned = warnings.any { it.cell == i },
                    onClick = { if (i in 1 until model.cells.size) editing = i },
                )
            }
        }
    }

    val index = editing
    if (index in 1..15 && index < model.cells.size) {
        val cell = model.cells[index]
        CellCorrectDialog(
            index = index,
            cell = cell,
            warnings = warnings.filter { it.cell == index },
            onEdit = { kind, beast, tier ->
                editing = -1
                onEdit(index + 1, kind, beast, tier)
            },
            onMarker = { on ->
                editing = -1
                onMarker(index + 1, on)
            },
            onDismiss = { editing = -1 },
        )
    }
}

@Composable
private fun LiveCellTile(
    index: Int,
    cell: Cell,
    wardenHere: Boolean,
    warned: Boolean,
    onClick: () -> Unit,
) {
    val border = when {
        warned -> Color(0xFFE0C860)
        wardenHere -> MaterialTheme.colorScheme.primary
        else -> Color(0xFF3A3A44)
    }
    Column(
        modifier = Modifier
            .aspectRatio(1.4f)
            .border(if (warned || wardenHere) 2.dp else 1.dp, border, RoundedCornerShape(6.dp))
            .background(if (warned) Color(0xFF2A2618) else Color(0xFF1A1A20), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(6.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(cellLabel(index), style = MaterialTheme.typography.labelSmall, color = Color(0xFF8A8A96))
            if (wardenHere) {
                Text("W", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(cell.summary(), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CellCorrectDialog(
    index: Int,
    cell: Cell,
    warnings: List<Snapshot.Warning>,
    onEdit: (kind: String, beast: String?, tier: Int?) -> Unit,
    onMarker: (on: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val marked = cell.event() != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cell ${cellLabel(index)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                warnings.forEach {
                    Text(it.text, style = MaterialTheme.typography.bodySmall, color = Color(0xFFE0C860))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = cell is Cell.Sealed,
                        onClick = { onEdit("locked", null, null) },
                        label = { Text("Locked") },
                    )
                    FilterChip(
                        selected = cell is Cell.Empty,
                        onClick = { onEdit("empty", null, null) },
                        label = { Text("Open") },
                    )
                    FilterChip(
                        selected = marked,
                        onClick = { onMarker(!marked) },
                        label = { Text("Marker") },
                    )
                }
                Text("Inmate", style = MaterialTheme.typography.titleSmall)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("", Modifier.width(30.dp))
                    TIERS.forEach { (name, colour) ->
                        Text(
                            name.take(3),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = colour,
                        )
                    }
                }
                BoardState.BEASTS.forEach { beast ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            beast.replaceFirstChar { it.uppercase() },
                            Modifier.width(30.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TIERS.forEachIndexed { tier, (_, colour) ->
                            val chosen = cell is Cell.Inmate && cell.beast == beast && cell.tier == tier
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(30.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (chosen) colour else colour.copy(alpha = 0.25f))
                                    .clickable { onEdit("inmate", beast, tier) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    beast.first().uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (chosen) Color.Black else Color.White,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

/** The cell's incident marker, if it has one. */
private fun Cell.event(): String? = when (this) {
    is Cell.Sealed -> event
    is Cell.Empty -> event
    is Cell.Inmate -> event
    is Cell.Scouted -> event
    is Cell.Scratch -> event
    else -> null
}
