package dev.warden.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.warden.android.data.BoardState
import dev.warden.android.data.BoardState.Companion.cellLabel
import dev.warden.android.data.Cell

/**
 * The hand board: the 4×4 prison grid (the Room at index 0, cells 02–16),
 * the day, the warden, the theme, the vault and the held instruments.
 * Every change goes straight back through [onChange]; the session is
 * re-installed from the board then.
 */
@Composable
fun BoardEditor(
    board: BoardState,
    rollouts: Int,
    onRollouts: (Int) -> Unit,
    onChange: (BoardState) -> Unit,
) {
    var editing by remember { mutableStateOf(-1) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Stepper(
                label = "Day",
                value = board.day,
                range = 1..BoardState.RUN_DAYS,
                onValue = { onChange(board.copy(day = it)) },
            )
            Dropdown(
                label = "Warden",
                options = (0..15).map { it to cellLabel(it) },
                selected = board.warden,
                onSelect = { onChange(board.copy(warden = it)) },
            )
            Dropdown(
                label = "Theme",
                options = BoardState.THEMES.map { (id, name) -> (id ?: -1) to name },
                selected = board.theme ?: -1,
                onSelect = { onChange(board.copy(theme = if (it < 0) null else it)) },
            )
            Stepper(
                label = "Rollouts",
                value = rollouts,
                range = 50..5000,
                step = 50,
                onValue = onRollouts,
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(16) { i ->
                CellTile(
                    index = i,
                    cell = board.cells[i],
                    wardenHere = board.warden == i,
                    onClick = { editing = i },
                )
            }
        }

        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Instruments", style = MaterialTheme.typography.titleSmall)
            Chips(
                options = BoardState.TOOL_KEYS,
                selected = board.tools,
                onSelect = { onChange(board.copy(tools = it)) },
            )
            Text("Vault", style = MaterialTheme.typography.titleSmall)
            Chips(
                options = BoardState.BEASTS,
                selected = board.vault,
                onSelect = { onChange(board.copy(vault = it)) },
            )
        }
    }

    if (editing >= 0 && editing < 16) {
        CellDialog(
            index = editing,
            cell = board.cells[editing],
            onDismiss = { editing = -1 },
            onApply = { cell ->
                val i = editing
                editing = -1
                if (i in board.cells.indices) {
                    onChange(board.copy(cells = board.cells.toMutableList().also { it[i] = cell }))
                }
            },
        )
    }
}

@Composable
private fun CellTile(
    index: Int,
    cell: Cell,
    wardenHere: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .aspectRatio(1.4f)
            .border(
                width = if (wardenHere) 2.dp else 1.dp,
                color = if (wardenHere) MaterialTheme.colorScheme.primary else Color(0xFF3A3A44),
                shape = RoundedCornerShape(6.dp),
            )
            .background(Color(0xFF1A1A20), RoundedCornerShape(6.dp))
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

private fun Cell.summary(): String = when (this) {
    is Cell.Office -> "Room"
    is Cell.Sealed -> "sealed" + mark()
    is Cell.Empty -> "open" + mark()
    is Cell.Inmate -> "$beast $tier" + (if (ivory) " †" else "") + mark()
    is Cell.Nest -> when (due) {
        null -> "nest →1d"
        0 -> "nest today"
        else -> "nest →${due + 1}d"
    }
    is Cell.Scouted -> "$beast $tier?" + mark()
    is Cell.Scratch -> "graffiti $tier" + mark()
}

private fun Cell.mark() = when (this) {
    is Cell.Sealed, is Cell.Empty, is Cell.Inmate, is Cell.Scouted, is Cell.Scratch -> {
        val e = when (this) {
            is Cell.Sealed -> event
            is Cell.Empty -> event
            is Cell.Inmate -> event
            is Cell.Scouted -> event
            is Cell.Scratch -> event
            else -> null
        }
        when (e) {
            "execution" -> " ⚡"
            "chain" -> " ⛓"
            "unknown" -> " ?"
            else -> ""
        }
    }
    else -> ""
}

@Composable
private fun CellDialog(
    index: Int,
    cell: Cell,
    onDismiss: () -> Unit,
    onApply: (Cell) -> Unit,
) {
    var draft by remember { mutableStateOf(cell) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cell ${cellLabel(index)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("State", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KindChip("Sealed", draft is Cell.Sealed) { draft = Cell.Sealed((draft as? Cell.Sealed)?.event) }
                    KindChip("Open", draft is Cell.Empty) { draft = Cell.Empty((draft as? Cell.Empty)?.event) }
                    KindChip("Inmate", draft is Cell.Inmate) { draft = (draft as? Cell.Inmate) ?: Cell.Inmate() }
                    KindChip("Nest", draft is Cell.Nest) { draft = (draft as? Cell.Nest) ?: Cell.Nest() }
                    KindChip("Scouted", draft is Cell.Scouted) { draft = (draft as? Cell.Scouted) ?: Cell.Scouted() }
                    KindChip("Graffiti", draft is Cell.Scratch) { draft = (draft as? Cell.Scratch) ?: Cell.Scratch() }
                }
                when (val d = draft) {
                    is Cell.Sealed -> EventPick(d.event) { draft = d.copy(event = it) }
                    is Cell.Empty -> EventPick(d.event) { draft = d.copy(event = it) }
                    is Cell.Inmate -> {
                        BeastPick(d.beast) { draft = d.copy(beast = it) }
                        Stepper("Tier", d.tier, 1..5) { draft = d.copy(tier = it) }
                        Check("Ivory (Tusk)", d.ivory) { draft = d.copy(ivory = it) }
                        EventPick(d.event) { draft = d.copy(event = it) }
                    }
                    is Cell.Nest -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(null to "tomorrow", 0 to "today", 1 to "2 days", 2 to "3 days").forEach { (v, name) ->
                            KindChip(name, d.due == v) { draft = d.copy(due = v) }
                        }
                    }
                    is Cell.Scouted -> {
                        BeastPick(d.beast) { draft = d.copy(beast = it) }
                        Stepper("Tier", d.tier, 1..5) { draft = d.copy(tier = it) }
                        EventPick(d.event) { draft = d.copy(event = it) }
                    }
                    is Cell.Scratch -> {
                        Stepper("Tier", d.tier, 1..5) { draft = d.copy(tier = it) }
                        EventPick(d.event) { draft = d.copy(event = it) }
                    }
                    Cell.Office -> {}
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(draft) }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun KindChip(label: String, on: Boolean, onClick: () -> Unit) {
    FilterChip(selected = on, onClick = onClick, label = { Text(label) })
}

@Composable
private fun BeastPick(beast: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BoardState.BEASTS.forEach { b ->
            KindChip(b.replaceFirstChar { it.uppercase() }, beast == b) { onSelect(b) }
        }
    }
}

@Composable
private fun EventPick(event: String?, onSelect: (String?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (listOf(null to "none") + BoardState.EVENTS.map { it to it }).forEach { (v, name) ->
            KindChip(name, event == v) { onSelect(v) }
        }
    }
}

@Composable
private fun Check(label: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    FilterChip(selected = on, onClick = { onToggle(!on) }, label = { Text(label) })
}

@Composable
private fun Stepper(
    label: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    onValue: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label:", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { if (value - step >= range.first) onValue(value - step) }) { Text("−") }
        Text(value.toString(), fontWeight = FontWeight.Bold)
        TextButton(onClick = { if (value + step <= range.last) onValue(value + step) }) { Text("+") }
    }
}

@Composable
private fun Dropdown(
    label: String,
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val name = options.firstOrNull { it.first == selected }?.second ?: "?"
    Column {
        TextButton(onClick = { open = true }) { Text("$label: $name") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        open = false
                        onSelect(id)
                    },
                )
            }
        }
    }
}

@Composable
private fun Chips(
    options: List<String>,
    selected: Set<String>,
    onSelect: (Set<String>) -> Unit,
) {
    // A plain Row on the landscape screen, scrolling sideways when the
    // chips do not fit.
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { o ->
            FilterChip(
                selected = o in selected,
                onClick = { onSelect(if (o in selected) selected - o else selected + o) },
                label = { Text(o) },
            )
        }
    }
}
