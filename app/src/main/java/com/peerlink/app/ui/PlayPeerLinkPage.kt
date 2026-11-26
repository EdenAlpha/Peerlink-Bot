package com.peerlink.app.ui

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peerlink.app.BuildConfig
import com.peerlink.app.bot.BotPacketCounts
import com.peerlink.app.bot.GameDatabaseUpdate
import com.peerlink.app.play.PlayRoomController
import com.peerlink.app.play.RoomState
import kotlinx.coroutines.delay
import java.text.NumberFormat

@Composable
internal fun PlayPeerLinkPage(
    ctx: Context,
    primeReady: Boolean,
    primeActive: Boolean,
    botSession: Boolean,
    traffic: BotPacketCounts,
    onExportLogs: () -> Unit,
    data: BotDataState,
    onChooseUpdate: () -> Unit,
    onUseInstalled: () -> Unit,
    onOpenPrime: () -> Unit,
) {
    val room by PlayRoomController.state.collectAsStateWithLifecycle()
    PlayPeerLinkContent(
        ctx, room, primeReady, primeActive, botSession, traffic, onExportLogs,
        data, onChooseUpdate, onUseInstalled, onOpenPrime,
        onCreateRoom = { recordMemory ->
            if (!BuildConfig.DEBUG || BotDataSelection.state.value.canCreateRoom) {
                PlayRoomController.createRoom(recordMemory = recordMemory)
            }
        },
    )
}

/** The room screen can render every state without starting any services. */
@Composable
internal fun PlayPeerLinkContent(
    ctx: Context,
    room: RoomState,
    primeReady: Boolean,
    primeActive: Boolean,
    botSession: Boolean,
    traffic: BotPacketCounts,
    onExportLogs: () -> Unit,
    data: BotDataState = BotDataState(),
    onChooseUpdate: () -> Unit = {},
    onUseInstalled: () -> Unit = {},
    onOpenPrime: () -> Unit = {},
    onCreateRoom: (Boolean) -> Unit = { PlayRoomController.createRoom(recordMemory = it) },
) {
    var confirmEnd by rememberSaveable { mutableStateOf(false) }
    var showData by rememberSaveable { mutableStateOf(false) }
    var showTools by rememberSaveable { mutableStateOf(false) }
    val canCreate = data.canCreateRoom

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxSize().testTag("bot-match-page"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "room") {
                when (room) {
                    RoomState.Idle -> MatchStartCard(data, onCreate = { onCreateRoom(false) })
                    is RoomState.Creating -> MatchProgressCard("Creating room", roomStatusText(room.note))
                    is RoomState.Ready -> MatchJoinCard(room, ctx, onEnd = { confirmEnd = true })
                    is RoomState.Failed -> MatchFailureCard(
                        "Couldn't open the room", room.reason, "Try again", canCreate,
                        onRetry = { onCreateRoom(room.recordMemoryOnRetry) },
                        onExportLogs = onExportLogs,
                        onWithoutRecording = if (room.recordMemoryOnRetry) ({ onCreateRoom(false) }) else null,
                    )
                    is RoomState.FilesNeeded -> MatchFailureCard(
                        "Game files needed",
                        "${room.missingFiles} of ${room.totalFiles} files couldn't be read. Make sure eFootball is installed and updated, then check again.",
                        "Check again", canCreate,
                        onRetry = { onCreateRoom(room.recordMemoryOnRetry) },
                        onExportLogs = onExportLogs,
                        onWithoutRecording = if (room.recordMemoryOnRetry) ({ onCreateRoom(false) }) else null,
                    )
                    RoomState.Ending -> MatchProgressCard("Ending session", "You can create another room when it closes.")
                }
            }
            if (room is RoomState.Ready && botSession) {
                item(key = "traffic") { BotTrafficCard(traffic) }
            }
            if (room.canEditGameData && BuildConfig.DEBUG) {
                item(key = "game-data") {
                    GameDataPanel(
                        data, showData, { showData = !showData }, onChooseUpdate, onUseInstalled,
                    )
                }
                item(key = "tools") {
                    MatchToolsPanel(
                        showTools, { showTools = !showTools }, primeReady,
                        primeActive, canCreate, room == RoomState.Idle, onOpenPrime, onExportLogs,
                        onRecord = { onCreateRoom(true) },
                    )
                }
            }
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End this bot session?", fontWeight = FontWeight.Bold) },
            text = { Text("Ending the session will disconnect an ongoing match.") },
            confirmButton = {
                TextButton(onClick = { confirmEnd = false; PlayRoomController.endSession() }) {
                    Text("End session", color = PL.red)
                }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep playing") } },
            containerColor = PL.surface,
        )
    }
}

@Composable
private fun MatchStartCard(data: BotDataState, onCreate: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF202B26), Color(0xFF1C2430), PL.elevated)))
            .border(1.dp, Color(0xFF34413C), shape),
    ) {
        PitchMarkings(Modifier.align(Alignment.TopEnd).width(152.dp).height(156.dp))
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Rounded.SportsSoccer, null, Modifier.size(24.dp), tint = PL.goldSoft)
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                MatchTitle("Play the bot")
                Text("Create a room here, then join it in eFootball.",
                    color = PL.inkSoft, fontSize = 14.sp, lineHeight = 21.sp)
            }
            MatchPrimaryButton("Create room", Icons.AutoMirrored.Rounded.ArrowForward,
                Modifier.testTag("bot-create-room"), enabled = data.canCreateRoom, onClick = onCreate)
        }
    }
}

@Composable
private fun PitchMarkings(modifier: Modifier) {
    Canvas(modifier) {
        val pitchColor = PL.goldSoft.copy(alpha = 0.13f)
        val stroke = Stroke(1.dp.toPx())
        val left = size.width * 0.26f
        val top = size.height * 0.16f
        val width = size.width * 0.93f
        val height = size.height * 0.66f
        val half = left + width * 0.5f
        val middle = top + height * 0.5f
        drawRect(pitchColor, Offset(left, top), Size(width, height), style = stroke)
        drawLine(pitchColor, Offset(half, top), Offset(half, top + height), strokeWidth = stroke.width)
        drawCircle(pitchColor, height * 0.16f, Offset(half, middle), style = stroke)
        drawCircle(pitchColor, 1.4.dp.toPx(), Offset(half, middle))
        drawRect(pitchColor, Offset(left, middle - height * 0.24f),
            Size(width * 0.18f, height * 0.48f), style = stroke)
        drawRect(pitchColor, Offset(left, middle - height * 0.12f),
            Size(width * 0.07f, height * 0.24f), style = stroke)
    }
}

@Composable
private fun MatchJoinCard(room: RoomState.Ready, ctx: Context, onEnd: () -> Unit) {
    fun copyRoom() {
        PlayRoomController.copyNumber(ctx, room.number)
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Room number copied", Toast.LENGTH_SHORT).show()
    }
    MatchCard {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            MatchTitle("Room ready")
            Text(room.note.takeIf { it.isNotBlank() }?.let(::roomStatusText)
                ?: "Join from Friend Match in eFootball.",
                color = PL.inkSoft, fontSize = 14.sp, lineHeight = 21.sp,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PL.bg)
                .clickable(role = Role.Button, onClickLabel = "Copy room number", onClick = ::copyRoom)
                .padding(start = 15.dp, end = 15.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Room number", color = PL.muted, fontSize = 12.sp)
                BasicText(
                    room.number, Modifier.testTag("bot-room-code"),
                    style = TextStyle(color = PL.ink, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                    maxLines = 1, softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 32.sp, stepSize = 1.sp),
                )
            }
            Icon(Icons.Rounded.ContentCopy, null, Modifier.size(20.dp), tint = PL.goldSoft)
        }
        MatchPrimaryButton("Copy and open eFootball", Icons.Rounded.NorthEast, Modifier.testTag("bot-open-game")) {
            copyRoom()
            val launch = ctx.packageManager.getLaunchIntentForPackage("jp.konami.pesam")
            if (launch == null || runCatching { ctx.startActivity(launch) }.isFailure) {
                Toast.makeText(ctx, "Open eFootball from your home screen", Toast.LENGTH_SHORT).show()
            }
        }
        TextButton(onClick = onEnd, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("bot-end-session")) {
            Text("End session", color = PL.inkSoft)
        }
    }
}

@Composable
private fun MatchProgressCard(title: String, note: String) {
    MatchCard {
        MatchTitle(title)
        LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
            color = PL.gold, trackColor = PL.line)
        Text(note, color = PL.inkSoft, fontSize = 14.sp, lineHeight = 20.sp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun MatchFailureCard(
    title: String,
    reason: String,
    retry: String,
    enabled: Boolean,
    onRetry: () -> Unit,
    onExportLogs: () -> Unit,
    onWithoutRecording: (() -> Unit)?,
) {
    MatchCard {
        Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(23.dp), tint = PL.red)
        MatchTitle(title)
        Text(reason, color = PL.inkSoft, fontSize = 14.sp, lineHeight = 21.sp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        MatchPrimaryButton(retry, Icons.Rounded.Refresh, enabled = enabled, onClick = onRetry)
        onWithoutRecording?.let {
            MatchSecondaryButton("Create room without recording", Icons.AutoMirrored.Rounded.ArrowForward, enabled, it)
        }
        TextButton(onClick = onExportLogs) {
            Icon(Icons.Rounded.Download, null, Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text("Export diagnostics")
        }
    }
}

@Composable
private fun MatchCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(PL.elevated).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
        content = content,
    )
}

@Composable
private fun MatchTitle(text: String) {
    Text(text, color = PL.ink, fontSize = 30.sp, lineHeight = 35.sp, letterSpacing = (-0.8).sp,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
}

@Composable
private fun GameDataPanel(
    data: BotDataState,
    expanded: Boolean,
    onExpand: () -> Unit,
    onChoose: () -> Unit,
    onUseInstalled: () -> Unit,
) {
    val pack = data.pack
    val summary = when {
        data.busy != null -> data.busy
        data.invalid -> "Update needs attention"
        pack != null -> "Saved update · ${pack.date}"
        else -> "Using installed game data"
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(PL.elevated)) {
        MatchDisclosure(
            Icons.Rounded.FolderOpen, "eFootball data", summary, expanded, onExpand,
            if (data.invalid) PL.red else if (pack != null) PL.greenGlow else PL.muted,
            tag = "bot-data-panel",
        )
        if (data.error != null || data.invalid) {
            val kept = when {
                data.invalid -> "Choose another file or use installed data."
                pack != null -> "Still using the saved update from ${pack.date}."
                else -> "Still using installed game data."
            }
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 10.dp)
                .clip(RoundedCornerShape(12.dp)).background(PL.red.copy(alpha = 0.09f)).padding(12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(data.error ?: "PeerLink couldn't verify its saved update.", color = PL.red,
                    fontSize = 13.sp, lineHeight = 19.sp)
                Text(kept, color = PL.inkSoft, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
        if (expanded) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (pack != null) "${GameDatabaseUpdate.FILE_NAME} is selected for the next room."
                    else "Choose a saved eFootball data file. The newest supported update is ${GameDatabaseUpdate.current.date}.",
                    color = PL.inkSoft, fontSize = 13.sp, lineHeight = 19.sp)
                MatchSecondaryButton(if (pack != null) "Replace saved update" else "Import update",
                    Icons.Rounded.UploadFile, enabled = data.busy == null, onClick = onChoose)
                if (pack != null || data.invalid) {
                    TextButton(enabled = data.busy == null, onClick = onUseInstalled) {
                        Text("Use installed data")
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchToolsPanel(
    expanded: Boolean,
    onExpand: () -> Unit,
    primeReady: Boolean,
    primeActive: Boolean,
    canCreate: Boolean,
    showExport: Boolean,
    onOpenPrime: () -> Unit,
    onExportLogs: () -> Unit,
    onRecord: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(PL.elevated)) {
        MatchDisclosure(Icons.Rounded.Tune, "Match tools",
            "Memory recording and diagnostics", expanded, onExpand, tag = "bot-tools-panel")
        if (expanded) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (primeActive) "Record memory use and free RAM before opening the room."
                    else "Memory recording requires Prime Mode. A regular room is available above.",
                    color = PL.inkSoft, fontSize = 13.sp, lineHeight = 19.sp)
                if (primeActive) {
                    MatchSecondaryButton("Record memory and create room", Icons.Rounded.Memory,
                        enabled = canCreate, onClick = onRecord)
                } else {
                    MatchSecondaryButton(if (primeReady) "Open Prime Mode" else "Set up Prime Mode",
                        Icons.Rounded.Bolt, onClick = onOpenPrime)
                }
                if (showExport) TextButton(onClick = onExportLogs) { Text("Export diagnostics") }
            }
        }
    }
}

@Composable
private fun MatchDisclosure(
    icon: ImageVector,
    label: String,
    detail: String,
    expanded: Boolean,
    onClick: () -> Unit,
    detailColor: Color = PL.muted,
    tag: String,
) {
    Row(
        Modifier.fillMaxWidth().testTag(tag).clickable(role = Role.Button,
            onClickLabel = if (expanded) "Hide details" else "Show details", onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(38.dp).background(PL.surface, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(19.dp), tint = PL.indigoGlow)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = PL.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = detailColor, fontSize = 12.sp, lineHeight = 17.sp,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null,
            Modifier.size(20.dp), tint = PL.muted)
    }
}

private fun roomStatusText(note: String): String = when {
    note.startsWith("room ", ignoreCase = true) && note.contains("open", ignoreCase = true) -> "Waiting for you to join in eFootball."
    note.startsWith("your game joined", ignoreCase = true) -> "Your game joined. The bot is getting ready."
    note.startsWith("ready sent", ignoreCase = true) -> "Waiting for both sides to be ready."
    note.startsWith("both sides ready", ignoreCase = true) -> "Both sides are ready. Connecting the match."
    else -> note.replace(" -- ", " — ")
}

@Composable
internal fun BotTrafficCard(traffic: BotPacketCounts) {
    val last = traffic.lastFromGameAtMs
    var recent by remember(last) { mutableStateOf(last >= 0 && SystemClock.elapsedRealtime() - last in 0L..<10_000L) }
    LaunchedEffect(last) {
        if (recent) {
            delay((last + 10_000L - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            recent = false
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(PL.elevated)
            .padding(16.dp).testTag("bot-traffic-card"),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Match traffic", color = PL.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(6.dp).background(if (recent) PL.green else PL.muted, CircleShape))
                Text(if (recent) "Receiving packets from your game"
                    else if (traffic.fromGame > 0) "No recent packets from your game" else "Waiting for your game",
                    color = PL.inkSoft, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        HorizontalDivider(color = PL.line)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TrafficCount("From your game", traffic.fromGame, Modifier.weight(1f))
            TrafficCount("Sent to your game", traffic.toGame, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TrafficCount(label: String, count: Long, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = PL.muted, fontSize = 12.sp, lineHeight = 17.sp)
        Text(NumberFormat.getIntegerInstance().format(count), color = PL.ink,
            fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MatchPrimaryButton(label: String, icon: ImageVector, modifier: Modifier = Modifier,
                               enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PL.gold, contentColor = PL.bg),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Icon(icon, null, Modifier.size(19.dp))
    }
}

@Composable
private fun MatchSecondaryButton(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(13.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = PL.surface, contentColor = PL.ink),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Icon(icon, null, Modifier.size(17.dp))
        Spacer(Modifier.width(9.dp))
        Text(label, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}
