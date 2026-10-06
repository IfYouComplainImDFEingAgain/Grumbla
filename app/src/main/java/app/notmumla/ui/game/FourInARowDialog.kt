package app.notmumla.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notmumla.game.Disc
import app.notmumla.game.FourBoard
import app.notmumla.game.GameState
import app.notmumla.ui.theme.MumbleTheme

/** What the game UI can ask for; maps 1:1 onto [app.notmumla.game.GameController]. */
class GameActions(
    val accept: () -> Unit,
    val decline: () -> Unit,
    val play: (col: Int) -> Unit,
    val quit: () -> Unit,
    val dismiss: () -> Unit,
    val rematch: (session: Int, name: String) -> Unit,
)

private val Red = Color(0xFFE53935)
private val Yellow = Color(0xFFFDD835)
private fun Disc.color() = if (this == Disc.RED) Red else Yellow

/** Shows whatever the current game state needs: an invite prompt, a wait, or the board. */
@Composable
fun GameOverlay(state: GameState, actions: GameActions) {
    val c = MumbleTheme.colors
    when (state) {
        GameState.Idle -> Unit
        is GameState.Invited -> AlertDialog(
            onDismissRequest = {}, // an outside tap shouldn't silently decline
            containerColor = c.surfContainer,
            title = { Text("Four in a Row", fontWeight = FontWeight.Bold, color = c.onSurface) },
            text = { Text("${state.peer.name} challenges you. They play red and go first.", color = c.onSurface) },
            confirmButton = { TextButton(onClick = actions.accept) { Text("Play") } },
            dismissButton = { TextButton(onClick = actions.decline) { Text("Decline") } },
        )
        is GameState.Inviting -> AlertDialog(
            onDismissRequest = {},
            containerColor = c.surfContainer,
            title = { Text("Four in a Row", fontWeight = FontWeight.Bold, color = c.onSurface) },
            text = {
                Text("Waiting for ${state.peer.name}… They need not-mumla with the game unlocked.",
                    color = c.onSurface)
            },
            confirmButton = { TextButton(onClick = actions.quit) { Text("Cancel") } },
        )
        is GameState.Playing -> BoardDialog(
            board = state.board,
            me = state.me,
            status = if (state.myTurn) "Your turn" else "${state.peer.name} is thinking…",
            onColumn = if (state.myTurn) actions.play else null,
            confirm = { TextButton(onClick = actions.quit) { Text("Forfeit") } },
        )
        is GameState.Ended -> {
            val rematch = @Composable {
                TextButton(onClick = { actions.rematch(state.peer.session, state.peer.name) }) { Text("Rematch") }
            }
            if (state.board != null && state.me != null) {
                BoardDialog(
                    board = state.board, me = state.me, status = state.message, onColumn = null,
                    confirm = { TextButton(onClick = actions.dismiss) { Text("Close") } },
                    dismiss = rematch,
                )
            } else {
                AlertDialog(
                    onDismissRequest = actions.dismiss,
                    containerColor = c.surfContainer,
                    title = { Text("Four in a Row", fontWeight = FontWeight.Bold, color = c.onSurface) },
                    text = { Text(state.message, color = c.onSurface) },
                    confirmButton = { TextButton(onClick = actions.dismiss) { Text("Close") } },
                )
            }
        }
    }
}

@Composable
private fun BoardDialog(
    board: FourBoard,
    me: Disc,
    status: String,
    onColumn: ((Int) -> Unit)?,
    confirm: @Composable () -> Unit,
    dismiss: (@Composable () -> Unit)? = null,
) {
    val c = MumbleTheme.colors
    val winning = board.winningLine?.toSet().orEmpty()
    AlertDialog(
        onDismissRequest = {},
        containerColor = c.surfContainer,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(me.color()))
                Text("  $status", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.onSurface)
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth()
                    .aspectRatio(FourBoard.COLS / FourBoard.ROWS.toFloat())
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.primaryContainer)
                    .padding(4.dp),
            ) {
                Row(Modifier.fillMaxSize()) {
                    for (col in 0 until FourBoard.COLS) {
                        val playable = onColumn != null && board.canDrop(col)
                        // The whole column is the touch target: aiming at a 30 dp hole is fiddly.
                        Column(
                            Modifier.weight(1f).fillMaxSize()
                                .let { if (playable) it.clickable { onColumn!!(col) } else it },
                            verticalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            for (row in 0 until FourBoard.ROWS) {
                                val disc = board[row, col]
                                Box(
                                    Modifier.weight(1f).fillMaxWidth().padding(3.dp)
                                        .aspectRatio(1f, matchHeightConstraintsFirst = true)
                                        .align(Alignment.CenterHorizontally)
                                        .clip(CircleShape)
                                        .background(disc?.color() ?: c.surface)
                                        .let {
                                            if ((row to col) in winning) it.border(3.dp, c.onSurface, CircleShape) else it
                                        },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = confirm,
        dismissButton = dismiss,
    )
}
