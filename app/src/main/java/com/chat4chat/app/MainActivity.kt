package com.chat4chat.app

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.Coil
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Coil.setImageLoader(ImageLoader.Builder(this).components {
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }.build())
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF8F78FF))
            else lightColorScheme(primary = Color(0xFF6C4CF5))
            MaterialTheme(colorScheme = colors) { Surface(Modifier.fillMaxSize()) { App() } }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("c4c", 0) }
    val scope = rememberCoroutineScope()
    var me by remember { mutableStateOf<Profile?>(null) }
    var checking by remember { mutableStateOf(true) }
    var room by remember { mutableStateOf<Room?>(null) }
    var peer by remember { mutableStateOf<Peer?>(null) }
    var tab by remember { mutableStateOf(0) }
    var blocked by remember { mutableStateOf(prefs.getStringSet("blocked", emptySet()) ?: emptySet()) }
    var sel by remember { mutableStateOf<Msg?>(null) }
    var account by remember { mutableStateOf(false) }
    var presence by remember { mutableStateOf(listOf<Pair<String?, Long>>()) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val ready = me != null

    LaunchedEffect(Unit) {
        me = runCatching { Repo.currentProfile() }.getOrNull()
        checking = false
    }
    DisposableEffect(ready) {
        val l = if (ready) Repo.listenPresence { presence = it } else null
        onDispose { l?.remove() }
    }
    LaunchedEffect(room, ready) {
        while (ready) {
            runCatching { Repo.heartbeat(room?.id) }
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val live = presence.filter { it.second > now - 90_000 }
    BackHandler(room != null || peer != null) { if (peer != null) peer = null else room = null }

    Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        val m = me
        val p = peer
        val r = room
        when {
            checking -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            m == null -> AuthScreen { me = it }
            p != null -> DmScreen(m, p, blocked, { peer = null }, { sel = it })
            r != null -> RoomScreen(r, m, ready, live.count { it.first == r.id }, blocked, { room = null },
                { msg -> if (msg.uid != m.uid) peer = Peer(msg.uid, msg.name, msg.photo) }, { sel = it })
            else -> Home(m, maxOf(live.size, 1), { id -> live.count { it.first == id } }, blocked, tab,
                { tab = it }, { room = it }, { peer = it }, { account = true })
        }
    }

    me?.let { m ->
        if (account) AccountDialog(m, { account = false }) {
            Repo.logout(); me = null; room = null; peer = null; account = false
        }
    }
    sel?.let { m ->
        AlertDialog(
            onDismissRequest = { sel = null },
            title = { Text("${m.name}’s message") },
            text = { Text("Report this message or block this person?") },
            confirmButton = {
                TextButton({
                    scope.launch { runCatching { Repo.report(m, Repo.uid()) } }
                    Toast.makeText(ctx, "Thanks, we’ll review it.", Toast.LENGTH_SHORT).show()
                    sel = null
                }) { Text("Report") }
            },
            dismissButton = {
                TextButton({
                    blocked = blocked + m.uid
                    prefs.edit().putStringSet("blocked", blocked).apply()
                    sel = null
                }) { Text("Block user") }
            },
        )
    }
}

@Composable
fun Pill(t: String) = Surface(shape = RoundedCornerShape(99.dp), color = MaterialTheme.colorScheme.primaryContainer) {
    Text(t, Modifier.padding(10.dp, 3.dp), fontSize = 13.sp)
}

@Composable
fun Avatar(photo: String, name: String, size: Dp = 32.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primary), Alignment.Center) {
        if (photo.isNotBlank()) AsyncImage(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(name.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, secret: Boolean = false,
          type: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().padding(vertical = 4.dp), label = { Text(label) },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else type),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
}

@Composable
fun AuthScreen(onDone: (Profile) -> Unit) {
    val scope = rememberCoroutineScope()
    var signup by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var full by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    var forgot by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { photo = it }

    fun go(block: suspend () -> Profile) {
        scope.launch {
            busy = true
            runCatching { block() }
                .onSuccess(onDone)
                .onFailure { err = if (it is IllegalStateException) it.message ?: "" else "Something went wrong. Check your connection." }
            busy = false
        }
    }

    if (forgot) { ResetScreen(user) { forgot = false }; return }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.mipmap.ic_launcher), null, Modifier.size(96.dp).clip(RoundedCornerShape(22.dp)))
        Spacer(Modifier.height(8.dp))
        Text(if (signup) "Create your account" else "Welcome back", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (signup) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                Alignment.Center) {
                if (photo != null) AsyncImage(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text("📷\nAdd photo", textAlign = TextAlign.Center, fontSize = 13.sp)
            }
            Spacer(Modifier.height(8.dp))
            Field(user, { user = it }, "Username")
            Field(email, { email = it }, "Email address", type = KeyboardType.Email)
            Field(full, { full = it }, "Full name")
            Field(age, { age = it.filter(Char::isDigit).take(3) }, "Age", type = KeyboardType.Number)
            Field(pw, { pw = it }, "Password", secret = true)
            Text("Only your username and photo are shown to other people. You must be $MIN_AGE or older.",
                fontSize = 12.sp, textAlign = TextAlign.Center)
        } else {
            Field(user, { user = it }, "Username or email")
            Field(pw, { pw = it }, "Password", secret = true)
            TextButton({ forgot = true }) { Text("Forgot password?") }
        }
        if (err.isNotBlank()) Text(err, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(12.dp))
        Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
            err = ""
            if (!signup) {
                if (user.isBlank() || pw.isBlank()) err = "Enter your username or email and password."
                else go { Repo.login(user, pw) }
            } else {
                val a = age.toIntOrNull()
                when {
                    !Regex("[A-Za-z0-9_]{3,20}").matches(user.trim()) -> err = "Username: 3–20 letters, numbers or _."
                    !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() -> err = "Enter a valid email address."
                    full.isBlank() -> err = "Enter your full name."
                    a == null || a < MIN_AGE -> err = "You must be $MIN_AGE or older to join."
                    pw.length < 6 -> err = "Password needs at least 6 characters."
                    else -> go { Repo.signUp(user, email, pw, full, a, photo) }
                }
            }
        }) { Text(if (busy) "Please wait…" else if (signup) "Sign up" else "Log in") }
        TextButton({ signup = !signup; err = "" }) {
            Text(if (signup) "Already have an account? Log in" else "New here? Sign up")
        }
    }
}

@Composable
fun ResetScreen(prefill: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var id by remember { mutableStateOf(prefill) }
    var sent by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.mipmap.ic_launcher), null, Modifier.size(96.dp).clip(RoundedCornerShape(22.dp)))
        Spacer(Modifier.height(8.dp))
        Text("Reset your password", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (!sent) {
            Text("Enter your username or email. If we find the account, we’ll email a 6-digit code to the email you signed up with.",
                fontSize = 13.sp, textAlign = TextAlign.Center)
            Field(id, { id = it }, "Username or email")
            Spacer(Modifier.height(8.dp))
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                err = ""
                if (id.isBlank()) err = "Enter your username or email."
                else scope.launch {
                    busy = true
                    runCatching { Repo.requestReset(id) }
                        .onSuccess { sent = true }
                        .onFailure { err = "Couldn’t send the code. Check your connection." }
                    busy = false
                }
            }) { Text(if (busy) "Please wait…" else "Send code") }
        } else {
            Text("If that account exists, a code is on its way. It expires in 15 minutes.",
                fontSize = 13.sp, textAlign = TextAlign.Center)
            Field(code, { code = it.filter(Char::isDigit).take(6) }, "6-digit code", type = KeyboardType.Number)
            Field(pw, { pw = it }, "New password", secret = true)
            Spacer(Modifier.height(8.dp))
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                err = ""
                if (code.length < 6) err = "Enter the 6-digit code."
                else if (pw.length < 6) err = "Password needs at least 6 characters."
                else scope.launch {
                    busy = true
                    runCatching { Repo.confirmReset(id, code, pw) }
                        .onSuccess {
                            Toast.makeText(ctx, "Password changed. Log in with your new password.", Toast.LENGTH_LONG).show()
                            onBack()
                        }
                        .onFailure {
                            err = if (it is IllegalStateException) it.message ?: "" else "Something went wrong. Check your connection."
                        }
                    busy = false
                }
            }) { Text(if (busy) "Please wait…" else "Change password") }
            TextButton({ sent = false; err = "" }) { Text("Send a new code (wait a minute first)") }
        }
        if (err.isNotBlank()) Text(err, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        TextButton(onBack) { Text("Back to log in") }
    }
}

@Composable
fun AccountDialog(me: Profile, onClose: () -> Unit, onOut: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var del by remember { mutableStateOf(false) }
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("@${me.username}") },
        text = {
            if (del) Column {
                Text("Enter your password to permanently delete your account.")
                Field(pw, { pw = it }, "Password", secret = true)
            } else Text("Manage your account.")
        },
        confirmButton = {
            if (del) TextButton({
                scope.launch {
                    runCatching { Repo.deleteAccount(me.username, pw) }
                        .onSuccess { onOut() }
                        .onFailure { Toast.makeText(ctx, it.message ?: "Couldn’t delete the account.", Toast.LENGTH_LONG).show() }
                }
            }) { Text("Delete forever") } else TextButton(onOut) { Text("Log out") }
        },
        dismissButton = {
            if (del) TextButton({ del = false }) { Text("Back") } else TextButton({ del = true }) { Text("Delete account") }
        },
    )
}

@Composable
fun rememberMsgs(room: String, ready: Boolean): List<Msg> {
    var l by remember(room) { mutableStateOf(listOf<Msg>()) }
    DisposableEffect(room, ready) {
        val reg = if (ready) Repo.listenMessages(room) { l = it } else null
        onDispose { reg?.remove() }
    }
    return l
}

@Composable
fun Home(me: Profile, total: Int, count: (String) -> Int, blocked: Set<String>, tab: Int, onTab: (Int) -> Unit,
         onOpen: (Room) -> Unit, onPeer: (Peer) -> Unit, onAccount: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(14.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.mipmap.ic_launcher), null, Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(10.dp))
            Text("Chat4Chat", Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Pill("👥 $total online")
            Spacer(Modifier.width(10.dp))
            Avatar(me.photo, me.username, 36.dp, Modifier.clickable { onAccount() })
        }
        TabRow(selectedTabIndex = tab) {
            listOf("Rooms", "Private messages").forEachIndexed { i, t -> Tab(tab == i, { onTab(i) }) { Text(t, Modifier.padding(12.dp)) } }
        }
        if (tab == 0) {
            LazyColumn(Modifier.padding(horizontal = 14.dp), contentPadding = PaddingValues(vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ROOMS) { r ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(r) }) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(r.icon, fontSize = 28.sp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.name, fontWeight = FontWeight.SemiBold)
                                Text(r.about, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Pill("👥 ${count(r.id)}")
                        }
                    }
                }
            }
        } else Inbox(me, blocked, onPeer)
    }
}

@Composable
fun Inbox(me: Profile, blocked: Set<String>, onOpen: (Peer) -> Unit) {
    var list by remember { mutableStateOf(listOf<Convo>()) }
    DisposableEffect(Unit) {
        val r = Repo.listenConvos { list = it }
        onDispose { r.remove() }
    }
    val shown = list.map { it to it.other(me.uid) }.filter { it.second.uid !in blocked }
    if (shown.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
            Text("No private messages yet.\nTap someone’s name in a chat room to message them.", textAlign = TextAlign.Center)
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.first.id }) { (c, p) ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(p) }.padding(14.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p.photo, p.name, 46.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(p.name, fontWeight = FontWeight.SemiBold)
                        Text(c.lastText, maxLines = 1, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun MessageList(msgs: List<Msg>, myUid: String, onUser: (Msg) -> Unit, onLong: (Msg) -> Unit) {
    val st = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) st.scrollToItem(msgs.lastIndex) }
    if (msgs.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No messages yet. Say hi! 👋") }
    } else {
        LazyColumn(Modifier.fillMaxSize(), st, contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(msgs, key = { it.id }) { m -> Bubble(m, myUid, onUser, onLong) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Bubble(m: Msg, myUid: String, onUser: (Msg) -> Unit, onLong: (Msg) -> Unit) {
    val mine = m.uid == myUid
    Row(Modifier.fillMaxWidth(), if (mine) Arrangement.End else Arrangement.Start, Alignment.Bottom) {
        if (!mine) {
            Avatar(m.photo, m.name, 34.dp, Modifier.clickable { onUser(m) })
            Spacer(Modifier.width(6.dp))
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 280.dp).combinedClickable(onClick = {}, onLongClick = { if (!mine) onLong(m) }),
        ) {
            Column(Modifier.padding(10.dp, 8.dp)) {
                Text(m.name, Modifier.clickable(enabled = !mine) { onUser(m) }, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                when (m.kind) {
                    "gif", "image" -> AsyncImage(m.url, null,
                        Modifier.padding(top = 4.dp).widthIn(max = 250.dp).heightIn(max = 250.dp).clip(RoundedCornerShape(12.dp)))
                    "video" -> VideoPlayer(m.url)
                    else -> Text(m.text)
                }
            }
        }
        if (mine) {
            Spacer(Modifier.width(6.dp))
            Avatar(m.photo, m.name, 34.dp)
        }
    }
}

@Composable
fun VideoPlayer(url: String) {
    val ctx = LocalContext.current
    val p = remember(url) { ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(url)); prepare() } }
    DisposableEffect(p) { onDispose { p.release() } }
    AndroidView({ PlayerView(it).apply { player = p } }, Modifier.padding(top = 4.dp).size(230.dp, 170.dp))
}

@Composable
fun GifPanel(onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    var gifs by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(q) { delay(400); gifs = runCatching { Repo.giphy(q) }.getOrDefault(emptyList()) }
    Column {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            placeholder = { Text("Search GIFs") }, singleLine = true)
        LazyVerticalGrid(GridCells.Fixed(2), Modifier.height(200.dp)) {
            gridItems(gifs) { g ->
                AsyncImage(g, null, Modifier.padding(3.dp).height(100.dp).fillMaxWidth().clickable { onPick(g) },
                    contentScale = ContentScale.Crop)
            }
        }
        Text("Powered by GIPHY", Modifier.padding(horizontal = 10.dp), fontSize = 11.sp)
    }
}

@Composable
fun RoomScreen(room: Room, me: Profile, ready: Boolean, online: Int, blocked: Set<String>, onBack: () -> Unit,
               onUser: (Msg) -> Unit, onLong: (Msg) -> Unit) {
    val msgs = rememberMsgs(room.id, ready).filter { it.uid !in blocked }
    ChatUi(room.icon + " " + room.name, room.about, "👥 ${maxOf(online, 1)}", msgs, me.uid, onBack, onUser, onLong) { k, t, u ->
        Repo.send(room.id, me, k, t, u)
    }
}

@Composable
fun DmScreen(me: Profile, peer: Peer, blocked: Set<String>, onBack: () -> Unit, onLong: (Msg) -> Unit) {
    val pair = Repo.pairId(me.uid, peer.uid)
    var l by remember(pair) { mutableStateOf(listOf<Msg>()) }
    DisposableEffect(pair) {
        val r = Repo.listenDm(pair) { l = it }
        onDispose { r.remove() }
    }
    ChatUi("@" + peer.name, "Private chat", null, l.filter { it.uid !in blocked }, me.uid, onBack, {}, onLong) { k, t, u ->
        Repo.sendDm(me, peer, k, t, u)
    }
}

@Composable
fun ChatUi(title: String, sub: String, badge: String?, msgs: List<Msg>, myUid: String, onBack: () -> Unit,
           onUser: (Msg) -> Unit, onLong: (Msg) -> Unit, onSend: suspend (String, String, String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var panel by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun send(kind: String, t: String = "", url: String = "") {
        scope.launch {
            runCatching { onSend(kind, t, url) }
                .onFailure { Toast.makeText(ctx, "Couldn’t send. Try again.", Toast.LENGTH_SHORT).show() }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            runCatching {
                val vid = ctx.contentResolver.getType(uri)?.startsWith("video") == true
                onSend(if (vid) "video" else "image", "", Repo.upload(uri, vid))
            }.onFailure { Toast.makeText(ctx, "Upload failed (max 25 MB).", Toast.LENGTH_SHORT).show() }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", Modifier.clickable { onBack() }.padding(horizontal = 12.dp), fontSize = 28.sp)
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(sub, fontSize = 12.sp)
            }
            if (badge != null) Pill(badge)
        }
        HorizontalDivider()
        Box(Modifier.weight(1f)) { MessageList(msgs, myUid, onUser, onLong) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        when (panel) {
            "E" -> LazyVerticalGrid(GridCells.Adaptive(44.dp), Modifier.height(160.dp)) {
                gridItems(EMOJIS) { e -> Text(e, Modifier.clickable { text += e }.padding(6.dp), fontSize = 26.sp) }
            }
            "G" -> GifPanel { url -> send("gif", url = url); panel = "" }
        }
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("😊", Modifier.clickable { panel = if (panel == "E") "" else "E" }.padding(6.dp), fontSize = 22.sp)
            Text("GIF", Modifier.clickable { panel = if (panel == "G") "" else "G" }.padding(6.dp), fontWeight = FontWeight.Bold)
            Text("📎", Modifier.clickable {
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }.padding(6.dp), fontSize = 22.sp)
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Message") }, maxLines = 3)
            Spacer(Modifier.width(6.dp))
            Button({ if (text.isNotBlank()) { send("text", text.trim()); text = "" } }) { Text("Send") }
        }
    }
}
