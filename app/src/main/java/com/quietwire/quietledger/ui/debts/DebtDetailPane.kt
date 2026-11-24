package com.quietwire.quietledger.ui.debts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.quietwire.quietledger.domain.model.BalanceChange
import com.quietwire.quietledger.ui.theme.moneyColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIMESTAMP = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")

/**
 * The right hand pane when the window is wide enough for two columns. Shows the
 * form for the selected debt, plus its recorded history, which is content worth
 * putting in space that was otherwise empty.
 *
 * The actions are pinned to the bottom rather than sitting at the end of the
 * scrolling content. With the form taller than the pane, Save was previously
 * below the fold, so the primary action was invisible until you scrolled.
 */
@Composable
fun DebtDetailPane(
    state: DebtEditorState?,
    history: List<BalanceChange>,
    onChange: ((DebtEditorState) -> DebtEditorState) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        if (state == null) {
            NothingSelected(Modifier.fillMaxSize())
            return@Surface
        }

        Column(Modifier.fillMaxSize().imePadding()) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, top = 24.dp)
            ) {
                Text(
                    if (state.isEditing) "Edit debt" else "New debt",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(20.dp))

                DebtForm(state = state, onChange = onChange)

                if (state.isEditing && history.isNotEmpty()) {
                    Spacer(Modifier.height(28.dp))
                    Text("History", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    history.forEach { HistoryRow(it) }
                }

                Spacer(Modifier.height(24.dp))
            }

            HorizontalDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onSave, enabled = state.isValid) { Text("Save") }
                TextButton(onClick = onCancel) { Text("Cancel") }
                if (state.isEditing) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(change: BalanceChange) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(change.description, style = MaterialTheme.typography.bodyMedium)
            Text(
                change.occurredAt.atZone(ZoneId.systemDefault()).format(TIMESTAMP),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            // Direction, not sentiment: on a debt a decrease is good news.
            Text(
                (if (change.isDecrease) "-" else "+") +
                    change.delta.absoluteValue.format(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (change.isDecrease) {
                    MaterialTheme.moneyColors.positive
                } else {
                    MaterialTheme.moneyColors.negative
                }
            )
            Text(
                change.current.format(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NothingSelected(modifier: Modifier = Modifier) {
    Column(
        modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Select a debt",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Pick one from the list to see its history and edit it.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Without a cap the line breaks badly and orphans the last word.
            modifier = Modifier.widthIn(max = 280.dp)
        )
    }
}
