package com.peerlink.app.ui

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.peerlink.app.BuildConfig
import com.peerlink.app.bot.BotPacketCounts
import com.peerlink.app.bot.GameDatabaseUpdate
import com.peerlink.app.play.PlayRoomController
import com.peerlink.app.play.RoomState
import java.text.NumberFormat
import kotlinx.coroutines.delay

/** The bot hosts; the player joins the displayed room in their own game. */
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
    val copyRoom = { number: String ->
        PlayRoomController.copyNumber(ctx, number)
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Room number copied", Toast.LENGTH_SHORT).show()
    }
    val openGame = {
        val launch = ctx.packageManager.getLaunchIntentForPackage("jp.konami.pesam")
        if (launch == null || runCatching { ctx.startActivity(launch) }.isFailure) {
            Toast.makeText(ctx, "Open eFootball from your home screen", Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("bot-match-page"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "heading") {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("YOUR MATCH", color = PL.gold, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("Play the bot", color = PL.ink, fontSize = 30.sp, lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp)
                Text("One phone. Your game. A new opponent.", color = PL.inkSoft, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
        item(key = "room") {
            MatchRoomCard(room, onCopyRoom = copyRoom, onOpenGame = openGame, onExportLogs = onExportLogs,
                onEnd = { confirmEnd = true }, canCreate = data.canCreateRoom, onCreateRoom = onCreateRoom,
                primeSetup = if (primeActive) null else onOpenPrime)
        }
        if (BuildConfig.DEBUG && room.canEditGameData) {
            item(key = "game-data") {
                GameDataPanel(data, onChooseUpdate, onUseInstalled)
            }
        }
        if (room is RoomState.Ready && botSession) {
            item(key = "traffic") { BotTrafficCard(traffic) }
        }
        if (BuildConfig.DEBUG && room == RoomState.Idle) {
            item(key = "export") {
                TextButton(onClick = onExportLogs) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Export diagnostics")
                }
            }
        }
        item(key = "steps") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (room is RoomState.Ready) "NEXT UP" else "HOW IT WORKS",
                    color = PL.muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(PL.elevated)
                        .border(1.dp, PL.line.copy(alpha = 0.8f), RoundedCornerShape(24.dp)).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    MatchStep("01", "Create your room", "The bot hosts and gives you a room number.")
                    MatchStep("02", "Join in eFootball", "Open the game on this phone and enter that number.")
                    MatchStep("03", "Get to the match", "Choose your team, then follow the game's match prompts.")
                }
            }
        }
        item(key = "prime") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = if (primeActive) PL.green else PL.indigo,
                    modifier = Modifier.size(17.dp))
                if (primeActive) {
                    Text("Prime Mode is active.", color = PL.muted, fontSize = 12.sp, lineHeight = 17.sp)
                } else {
                    TextButton(onClick = onOpenPrime) {
                        Text(if (primeReady) "Open Prime Mode" else "Set up Prime Mode")
                    }
                }
            }
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End this bot session?", fontWeight = FontWeight.Bold) },
            text = { Text("The bot and its connection will stop. A match in progress may disconnect.") },
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
private fun MatchRoomCard(
    room: RoomState,
    onCopyRoom: (String) -> Unit,
    onOpenGame: () -> Unit,
    onExportLogs: () -> Unit,
    onEnd: () -> Unit,
    canCreate: Boolean,
    onCreateRoom: (Boolean) -> Unit,
    primeSetup: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF273229), Color(0xFF1C2524), Color(0xFF20213A))))
            .border(1.dp, PL.gold.copy(alpha = 0.22f), shape)
            .animateContentSize().padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(PL.gold.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.SportsSoccer, null, tint = PL.gold, modifier = Modifier.size(23.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("PEERLINK BOT", color = PL.gold, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text(
                    when (room) {
                        is RoomState.Ready -> "Room ready"
                        is RoomState.Creating -> "Preparing your room"
                        is RoomState.Ending -> "Ending session"
                        is RoomState.Failed, is RoomState.FilesNeeded -> "Let's try again"
                        RoomState.Idle -> "Bot hosts, you join"
                    },
                    color = PL.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            if (room is RoomState.Ready) Icon(Icons.Rounded.CheckCircle, null, tint = PL.green, modifier = Modifier.size(21.dp))
        }

        when (room) {
            RoomState.Idle -> {
                Text("A match,\nmade simple.", color = PL.ink, fontSize = 27.sp, lineHeight = 31.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp)
                Text("Create a room here, then join it from eFootball on this phone.",
                    color = PL.inkSoft, fontSize = 14.sp, lineHeight = 20.sp)
                MatchPrimaryButton("Create room", Icons.Rounded.ArrowForward, Modifier.testTag("bot-create-room"),
                    enabled = canCreate) {
                    onCreateRoom(false)
                }
            }
            is RoomState.Creating -> {
                Text("Setting things up", color = PL.ink, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    color = PL.gold, trackColor = Color.White.copy(alpha = 0.10f))
                Text(room.note, color = PL.inkSoft, fontSize = 13.sp, lineHeight = 19.sp)
                Text("No extra game download will start.", color = PL.muted, fontSize = 11.5.sp)
            }
            is RoomState.Ready -> {
                Text("YOUR ROOM NUMBER", color = PL.goldSoft, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                BasicText(room.number, Modifier.testTag("bot-room-code"),
                    style = TextStyle(color = PL.ink, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                    maxLines = 1, softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 34.sp, stepSize = 1.sp))
                Text("Enter this number in eFootball to join the bot.", color = PL.inkSoft, fontSize = 13.sp, lineHeight = 19.sp)
                if (room.note.isNotBlank()) {
                    Text(roomStatusText(room.note), color = PL.goldSoft, fontSize = 12.sp, lineHeight = 17.sp)
                }
                room.diagnosticsNote?.let {
                    Text(it, color = PL.inkSoft, fontSize = 12.sp, lineHeight = 17.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                MatchPrimaryButton("Copy and open eFootball", Icons.Rounded.NorthEast, Modifier.testTag("bot-open-game")) {
                    onCopyRoom(room.number)
                    onOpenGame()
                }
                MatchSecondaryButton("Copy room number", Icons.Rounded.ContentCopy) { onCopyRoom(room.number) }
                TextButton(onClick = onEnd, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("bot-end-session")) {
                    Text("End bot session", color = PL.inkSoft, fontSize = 12.sp)
                }
            }
            is RoomState.FilesNeeded -> {
                Text("Couldn't read game files", color = PL.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("${room.missingFiles} files were missing or unreadable. Check that eFootball is installed, then try again.",
                    color = PL.inkSoft, fontSize = 13.sp, lineHeight = 19.sp)
                MatchPrimaryButton(if (room.recordMemoryOnRetry) "Retry RAM cleanup and create room" else "Check again", Icons.Rounded.Refresh, enabled = canCreate) {
                    onCreateRoom(room.recordMemoryOnRetry)
                }
            }
            is RoomState.Failed -> {
                Text("Couldn't open the room", color = PL.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(room.reason, color = PL.inkSoft, fontSize = 13.sp, lineHeight = 19.sp)
                MatchPrimaryButton(if (room.recordMemoryOnRetry) "Retry RAM cleanup and create room" else "Try again", Icons.Rounded.Refresh, enabled = canCreate) {
                    onCreateRoom(room.recordMemoryOnRetry)
                }
            }
            RoomState.Ending -> {
                Text("Closing your bot session", color = PL.ink, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    color = PL.gold, trackColor = Color.White.copy(alpha = 0.10f))
                Text("You can start another room when this finishes.", color = PL.inkSoft, fontSize = 13.sp)
            }
        }
        if (BuildConfig.DEBUG && room.canEditGameData) {
            val retryRecords = (room is RoomState.Failed && room.recordMemoryOnRetry) ||
                (room is RoomState.FilesNeeded && room.recordMemoryOnRetry)
            if (retryRecords) {
                if (primeSetup != null) TextButton(onClick = primeSetup) { Text("Open Prime Mode") }
                MatchSecondaryButton("Create room without recording", Icons.Rounded.ArrowForward, enabled = canCreate) {
                    onCreateRoom(false)
                }
            } else {
                MatchSecondaryButton("Clear RAM and create room", Icons.Rounded.Memory, enabled = canCreate,
                    modifier = Modifier.testTag("bot-clear-ram")) {
                    onCreateRoom(true)
                }
                Text("Prime clears background apps, watches RAM during play, and saves match diagnostics.",
                    color = PL.inkSoft, fontSize = 11.5.sp, lineHeight = 17.sp)
            }
        }
        if (room is RoomState.Failed || room is RoomState.FilesNeeded) {
            MatchSecondaryButton("Export diagnostics", Icons.Rounded.Download, onClick = onExportLogs)
        }
    }
}

@Composable
private fun GameDataPanel(data: BotDataState, onChoose: () -> Unit, onUseInstalled: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(PL.elevated)
            .border(1.dp, PL.line, shape).padding(18.dp).testTag("bot-data-panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("eFootball data", color = PL.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        val pack = data.pack
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            data.error?.let { Text(it, color = PL.red, fontSize = 13.sp, lineHeight = 18.sp) }
            val status = when {
                data.busy != null -> data.busy
                data.invalid -> "Choose another file or use installed data."
                pack != null && data.error != null -> "Still using the saved update from ${pack.date}."
                pack != null -> "Saved update · ${pack.date}"
                data.error != null -> "Still using installed game data."
                else -> "Using installed game data. The latest supported update is ${GameDatabaseUpdate.current.date}."
            }
            Text(status, color = PL.inkSoft, fontSize = 13.sp, lineHeight = 18.sp)
        }
        MatchSecondaryButton(if (pack != null) "Replace saved update" else "Import update",
            Icons.Rounded.FolderOpen, enabled = data.busy == null, onClick = onChoose)
        if (pack != null || data.invalid) TextButton(enabled = data.busy == null, onClick = onUseInstalled) {
            Text("Use installed data", color = PL.inkSoft)
        }
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
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(PL.elevated)
            .border(1.dp, PL.line.copy(alpha = 0.8f), shape).padding(18.dp).testTag("bot-traffic-card"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Match traffic", color = PL.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(if (recent) "Receiving packets from your game" else if (traffic.fromGame > 0) "Waiting for the next game packet" else "Waiting for your game",
                    color = PL.muted, fontSize = 11.5.sp, lineHeight = 16.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(7.dp).background(if (recent) PL.green else PL.muted, CircleShape))
                Text(if (recent) "LIVE" else "WAITING", color = if (recent) PL.green else PL.muted,
                    fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
            }
        }
        HorizontalDivider(color = PL.line)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TrafficCount("From your game", traffic.fromGame, PL.gold, Modifier.weight(1f))
            TrafficCount("Sent to your game", traffic.toGame, PL.indigoGlow, Modifier.weight(1f))
        }
        Text("Counts direct and VPN packets. Sent means accepted by this phone's socket.",
            color = PL.muted, fontSize = 10.5.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun TrafficCount(label: String, count: Long, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = PL.inkSoft, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(NumberFormat.getIntegerInstance().format(count), color = color, fontSize = 22.sp,
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MatchStep(number: String, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(PL.gold.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center) {
            Text(number, color = PL.gold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = PL.ink, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = PL.muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun MatchPrimaryButton(label: String, icon: ImageVector, modifier: Modifier = Modifier,
                               enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = PL.gold, contentColor = PL.bg)) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Icon(icon, null, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun MatchSecondaryButton(label: String, icon: ImageVector, enabled: Boolean = true,
                                 modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(15.dp), border = androidx.compose.foundation.BorderStroke(1.dp, PL.lineSoft),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = PL.ink)) {
        Icon(icon, null, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}
