package com.vayunmathur.games.chess

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.vayunmathur.games.chess.data.Move
import com.vayunmathur.games.chess.data.Piece
import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.PieceType
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.util.ChessAchievementsManager
import com.vayunmathur.games.chess.util.AppBackupAgent
import com.vayunmathur.games.chess.util.ChessActions
import com.vayunmathur.games.chess.util.ChessUiState
import com.vayunmathur.games.chess.util.ChessViewModel
import com.vayunmathur.games.chess.util.Difficulty
import com.vayunmathur.games.chess.util.GameMode
import com.vayunmathur.games.chess.util.GameResult
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SegmentedButton
import com.vayunmathur.library.ui.SegmentedButtonDefaults
import com.vayunmathur.library.ui.SingleChoiceSegmentedButtonRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.VerticalDivider
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Board cap + moves/history panel width for the expanded (>=840dp) game layout. */
internal val ChessBoardMaxWidth = 560.dp
internal val ChessSidePanelWidth = 300.dp

/**
 * The new-game picker.
 *
 * [aiAvailable] defaults true so the store-listing preview renders the full dialog. On a real
 * device it comes from [ChessViewModel.aiAvailable], and the AI button is hidden rather than
 * disabled when the model cannot run: a mode that can never move is not worth explaining.
 */
@Composable
fun NewGameDialog(onNewGame: (GameMode) -> Unit, onDismiss: () -> Unit = {}, aiAvailable: Boolean = true) {
    var showSettings by remember { mutableStateOf<((PieceColor, Difficulty) -> Unit)?>(null) }

    showSettings?.let { startGame ->
        var selectedColor by remember { mutableStateOf(PieceColor.WHITE) }
        var selectedDifficulty by remember {mutableStateOf(Difficulty.INTERMEDIATE)}
        AlertDialog(
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            modifier = Modifier.fillMaxWidth(0.9f),
            onDismissRequest = { showSettings = null },
            title = { Text(stringResource(R.string.start_new_game)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.play_as))
                        SingleChoiceSegmentedButtonRow {
                            PieceColor.entries.zip(listOf(stringResource(R.string.color_white), stringResource(R.string.color_black)))
                                .forEachIndexed { idx, (value, label) ->
                                    SegmentedButton(
                                        shape = SegmentedButtonDefaults.itemShape(idx, 2),
                                        onClick = { selectedColor = value },
                                        selected = selectedColor == value,
                                        label = {
                                            Text(
                                                label,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    )
                                }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    SingleChoiceSegmentedButtonRow {
                        Difficulty.entries.zip(listOf(stringResource(R.string.difficulty_easy), stringResource(R.string.difficulty_medium), stringResource(R.string.difficulty_hard), stringResource(R.string.difficulty_master))).forEachIndexed { idx, (value, label) ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(idx, 4),
                                onClick = { selectedDifficulty = value },
                                selected = selectedDifficulty == value,
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button({
                        startGame(selectedColor, selectedDifficulty)
                    }, Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.start_game))
                    }
                }
            },
            confirmButton = { }
        )
    } ?:
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(text = stringResource(R.string.new_game)) },
            text = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(onClick = { onNewGame(GameMode.TwoPlayer) }) {
                        Text(stringResource(R.string.two_player_local))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (aiAvailable) {
                        Button(onClick = {
                            showSettings = { color, difficulty ->
                                onNewGame(GameMode.VsAI(color, difficulty))
                            }
                        }) {
                            Text(stringResource(R.string.human_vs_ai))
                        }
                    }
                }
            },
            confirmButton = {}
        )
}

/** Binds [ChessViewModel] to the stateless [ChessGameScreen] and drives the achievements. */
@Composable
fun ChessGame(
    viewModel: ChessViewModel,
    onNewGame: () -> Unit,
    onOpenGameCenter: () -> Unit,
    achievementsManager: AchievementsManager
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(uiState.board.lastMove) {
        val lastMove = uiState.board.lastMove ?: return@LaunchedEffect
        // The engine's replies land in the same board state as the human's moves, so credit the
        // move only when the side that made it is the one the player is sitting on.
        val playedByPlayer = when (val mode = uiState.gameMode) {
            is GameMode.VsAI -> lastMove.piece.color == mode.playerColor
            GameMode.TwoPlayer -> true
        }
        if (!playedByPlayer) return@LaunchedEffect
        if (lastMove.isCastling) {
            achievementsManager.onAchievementUnlocked("castled")
        }
        if (lastMove.promotedTo != null) {
            achievementsManager.onAchievementUnlocked("promoted")
        }
    }

    LaunchedEffect(uiState.gameResult) {
        // Drive achievements off the typed game result, not the localized status text, so they
        // work in every locale. Only a checkmate counts as a "win"; draws unlock nothing here.
        val result = uiState.gameResult as? GameResult.Checkmate ?: return@LaunchedEffect
        val mode = uiState.gameMode
        val playerWins = when (mode) {
            is GameMode.VsAI -> result.winner == mode.playerColor
            GameMode.TwoPlayer -> true // local play: the side that delivered mate is "the player"
        }

        if (playerWins) {
            achievementsManager.onAchievementUnlocked("first_mate")

            if (uiState.board.moves.size <= 40) {
                achievementsManager.onAchievementUnlocked("won_fast")
            }

            if (mode is GameMode.VsAI && mode.difficulty >= Difficulty.ADVANCED) {
                achievementsManager.onAchievementUnlocked("win_vs_ai_hard")
            }

            // Only tick the persistent win counter once per game. This effect re-runs when the
            // Game screen re-enters composition (e.g. returning from the achievements screen), and
            // the outcome is still a win, so without this guard the count would climb each time.
            if (viewModel.claimWinScoring()) {
                val ds = DataStoreUtils.getInstance(context)
                val currentWins = (ds.getLong("chess_wins_count") ?: 0L) + 1
                ds.setLong("chess_wins_count", currentWins)
                achievementsManager.onProgressUpdated("win_10", currentWins.toInt())
                achievementsManager.onProgressUpdated("win_50", currentWins.toInt())
            }
        }
    }

    ChessGameScreen(
        state = uiState,
        actions = viewModel,
        onNewGame = onNewGame,
        onOpenGameCenter = onOpenGameCenter
    )
}

/**
 * The play screen, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 * Nothing here touches the model; the engine is only ever reached through the ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChessGameScreen(
    state: ChessUiState,
    actions: ChessActions,
    onNewGame: () -> Unit,
    onOpenGameCenter: () -> Unit
) {
    if (state.board.promotionPosition != null) {
        PawnPromotionDialog(state.turn, onPromote = actions::onPromote)
    }

    // No title: the tab is already identified by the bottom bar, so the app bar exists only to
    // hold the achievements button.
    AppScaffold(
        title = {},
        actions = {
            IconButton(onClick = onOpenGameCenter) {
                Icon(painterResource(id = android.R.drawable.btn_star_big_on), "Achievements")
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { innerPadding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Sized by the caller: the space left for the board differs per orientation, and in
            // portrait it is whatever the surrounding chrome does not take.
            val boardComposable = @Composable { side: Dp ->
                Box(Modifier.size(side)) {
                    BoardGrid(
                        board = state.board,
                        selectedPiece = state.selectedPiece,
                        isFlipped = state.isBoardFlipped,
                        turn = state.turn,
                        onSquareClick = actions::onSquareClick
                    )
                }
            }
            // Read in this scope: BoxWithConstraintsScope members are not reachable by implicit
            // receiver from inside the Row/Column lambdas below.
            val fullSide = minOf(maxWidth, maxHeight)
            // Expanded windows (>=840dp) get the side-panel treatment gated on the width
            // class, not orientation: the board is capped and centred with the
            // moves/history panel beside it, and the board itself never splits.
            // Smaller windows keep the orientation-based layout below.
            if (isExpandedWidth()) {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Box(Modifier.widthIn(max = ChessBoardMaxWidth).fillMaxWidth()) {
                            BoardGrid(
                                board = state.board,
                                selectedPiece = state.selectedPiece,
                                isFlipped = state.isBoardFlipped,
                                turn = state.turn,
                                onSquareClick = actions::onSquareClick
                            )
                        }
                    }
                    Column(
                        Modifier
                            .width(ChessSidePanelWidth)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CapturedPiecesRow(state.board.capturedByBlack)
                        MovesList(moves = state.board.moves, turn = state.turn)
                        CapturedPiecesRow(state.board.capturedByWhite)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onNewGame) {
                            Text(stringResource(R.string.new_game))
                        }
                        state.gameStatus?.let {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(it, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (maxWidth > maxHeight) {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    boardComposable(fullSide)
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CapturedPiecesRow(state.board.capturedByBlack)
                        MovesList(moves = state.board.moves, turn = state.turn)
                        CapturedPiecesRow(state.board.capturedByWhite)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onNewGame) {
                            Text(stringResource(R.string.new_game))
                        }
                        state.gameStatus?.let {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(it, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                // Everything except the board is laid out at its own height, so the New Game button
                // is always on screen; the board takes what is left over and shrinks instead. A
                // fixed full-width board plus this much chrome overflows the screen at large
                // display or font sizes, which used to push the button out of reach entirely.
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CapturedPiecesRow(state.board.capturedByBlack)
                    MovesList(moves = state.board.moves, turn = state.turn)
                    BoxWithConstraints(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        boardComposable(minOf(maxWidth, maxHeight))
                    }
                    CapturedPiecesRow(state.board.capturedByWhite)
                    Button(onClick = onNewGame) {
                        Text(stringResource(R.string.new_game))
                    }

                    state.gameStatus?.let {
                        Text(it, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun MovesList(moves: List<Move>, turn: PieceColor) {
    Box(Modifier.height(100.dp)) {
        LazyColumn(
            Modifier
                .fillMaxHeight()
                .border(2.dp, Color.Gray)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                    Text(stringResource(R.string.color_white), fontWeight = if (turn == PieceColor.WHITE) FontWeight.Bold else FontWeight.Normal)
                    VerticalDivider(color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.color_black), fontWeight = if (turn == PieceColor.BLACK) FontWeight.Bold else FontWeight.Normal)
                }
            }
            items(moves.chunked(2)) { move ->
                Row(Modifier.fillMaxWidth()) {
                    Text(move[0].toString(), Modifier.weight(1f), textAlign = TextAlign.Center)
                    if (move.size == 2) {
                        VerticalDivider(color = MaterialTheme.colorScheme.primary)
                        Text(move[1].toString(), Modifier.weight(1f), textAlign = TextAlign.Center)
                    } else {
                        VerticalDivider()
                        Text("", Modifier.weight(1f), textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
fun PawnPromotionDialog(color: PieceColor, onPromote: (PieceType) -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        title = { Text(text = stringResource(R.string.promote_pawn)) },
        text = {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
                for (pieceType in listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)) {
                    Box(Modifier.clickable { onPromote(pieceType) }) {
                        ChessPiece(Piece(pieceType, color), 64.dp)
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
fun CapturedPiecesRow(pieces: List<Piece>) {
    Box(
        Modifier
            .height(48.dp)
            .padding(8.dp), Alignment.Center) {
        if (pieces.isNotEmpty()) {
            Card {
                Row(
                    Modifier.padding(4.dp),
                ) {
                    pieces.forEach { ChessPiece(it, 32.dp) }
                }
            }
        }
    }
}

// File-scope so the chess board doesn't allocate 32 Color instances per recomposition.
private val lightSquareColor = Color(0xFFBBBBBB)
private val darkSquareColor = Color.Gray
private val lastMoveColor = Color(0xFF4CAF50)
private val moveHintColor = Color(0x66000000)

@Composable
fun BoardGrid(
    board: com.vayunmathur.games.chess.data.Board,
    selectedPiece: Position?,
    isFlipped: Boolean,
    turn: PieceColor,
    onSquareClick: (Position) -> Unit,
    showLastMove: Boolean = false
) {
    val isKingInCheck = board.isKingInCheck(turn)
    // The squares the selected piece can legally move to (for move-hint dots).
    val destinations = remember(board, selectedPiece) {
        val sel = selectedPiece
        if (sel == null) emptySet()
        else buildSet {
            for (i in board.pieces.indices) for (j in board.pieces[i].indices) {
                if (board.isValidMove(sel, Position(i, j))) add(Position(i, j))
            }
        }
    }
    // The puzzle board seeds a synthetic zero-length lastMove to encode turn; skip it.
    val lastMove = board.lastMove?.takeIf { showLastMove && it.start != it.end }
    Column(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .graphicsLayer { if (isFlipped) rotationZ = 180f }
    ) {
        for (i in board.pieces.indices) {
            Row(modifier = Modifier.weight(1f)) {
                for (j in board.pieces[i].indices) {
                    val piece = board.pieces[i][j]
                    val isSelected = selectedPiece?.let { it.row == i && it.col == j } ?: false
                    val isKingInCheckSquare =
                        isKingInCheck && piece?.type == PieceType.KING && piece.color == turn
                    val isLastMoveSquare = lastMove?.let {
                        (it.start.row == i && it.start.col == j) || (it.end.row == i && it.end.col == j)
                    } ?: false
                    val color = if ((i + j) % 2 == 0) lightSquareColor else darkSquareColor

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .background(color)
                            .clickable {
                                onSquareClick(Position(i, j))
                            }
                            .then(
                                when {
                                    isSelected -> Modifier.border(2.dp, Color.Yellow)
                                    isKingInCheckSquare -> Modifier.border(2.dp, Color.Red)
                                    isLastMoveSquare -> Modifier.border(3.dp, lastMoveColor)
                                    else -> Modifier
                                }
                            )
                    ) {
                        piece?.let {
                            ChessPiece(it, isFlipped = isFlipped)
                        }
                        if (Position(i, j) in destinations) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (piece != null) {
                                    // Capturable square: a ring around the piece.
                                    Box(
                                        Modifier
                                            .fillMaxSize(0.92f)
                                            .border(3.dp, moveHintColor, CircleShape)
                                    )
                                } else {
                                    // Empty square: a centered dot.
                                    Box(
                                        Modifier
                                            .fillMaxSize(0.30f)
                                            .clip(CircleShape)
                                            .background(moveHintColor)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChessPiece(piece: Piece, size: Dp? = null, isFlipped: Boolean = false) {
    val description = when(piece.color) {
        PieceColor.WHITE -> when(piece.type) {
            PieceType.KING -> stringResource(R.string.piece_white_king)
            PieceType.QUEEN -> stringResource(R.string.piece_white_queen)
            PieceType.ROOK -> stringResource(R.string.piece_white_rook)
            PieceType.BISHOP -> stringResource(R.string.piece_white_bishop)
            PieceType.KNIGHT -> stringResource(R.string.piece_white_knight)
            PieceType.PAWN -> stringResource(R.string.piece_white_pawn)
        }
        PieceColor.BLACK -> when(piece.type) {
            PieceType.KING -> stringResource(R.string.piece_black_king)
            PieceType.QUEEN -> stringResource(R.string.piece_black_queen)
            PieceType.ROOK -> stringResource(R.string.piece_black_rook)
            PieceType.BISHOP -> stringResource(R.string.piece_black_bishop)
            PieceType.KNIGHT -> stringResource(R.string.piece_black_knight)
            PieceType.PAWN -> stringResource(R.string.piece_black_pawn)
        }
    }
    Image(
        painterResource(id = piece.type.resID),
        description,
        (if (size != null) Modifier.size(size) else Modifier.fillMaxSize())
            .graphicsLayer { if (isFlipped) rotationZ = 180f },
        colorFilter = ColorFilter.tint(if (piece.color == PieceColor.WHITE) Color.White else Color.Black)
    )
}

@Composable
fun rememberAchievementsManager(): AchievementsManager? {
    val context = LocalContext.current
    val state = produceState<AchievementsManager?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) {
            val json = context.assets.open("achievements.json").bufferedReader().use { it.readText() }
            ChessAchievementsManager(context, json)
        }
    }
    return state.value
}
