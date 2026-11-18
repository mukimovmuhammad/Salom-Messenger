package com.mukimov.salom

import android.content.Context
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.paint
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.launch

internal val Yellow = Color(0xFFFFC400)
internal val Ink = Color(0xFF25241F)
private val Soft = Color(0xFFF5F5F3)
internal data class Person(val id: String, val name: String, val initials: String, val color: Color, val preview: String, val username: String = "", val avatarUrl: String = "")
internal data class Message(val text: String, val mine: Boolean, val time: String = "", val remoteId: Long = 0, val attachment: Attachment? = null, val receipt: String = "sent", val replyToId: Long? = null, val replyAuthor: String? = null, val replyPreview: String? = null, val edited: Boolean = false, val locationId: String? = null)
internal data class IncomingCallIntent(val callId: String, val peerId: String, val peerName: String, val action: String? = null)
private val savedPerson = Person("saved", "Избранное", "★", Yellow, "Сообщения и заметки для себя")

class MainActivity : ComponentActivity() {
    private val updater by lazy { androidx.lifecycle.ViewModelProvider(this)[AppUpdater::class.java] }
    private var notificationPeer by mutableStateOf<String?>(null)
    private var incomingCall by mutableStateOf<IncomingCallIntent?>(null)
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationPeer = intent.getStringExtra("peer_id")
        incomingCall = readCallIntent(intent)
        enableEdgeToEdge()
        PushNotifications.createChannel(this)
        setContent { SalomApp(this, updater, notificationPeer, incomingCall, { notificationPeer = null }) { incomingCall = null } }
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationPeer = intent.getStringExtra("peer_id")
        incomingCall = readCallIntent(intent)
    }

    private fun readCallIntent(intent: android.content.Intent) = intent.getStringExtra("incoming_call_id")?.let { callId ->
        val peerId = intent.getStringExtra("incoming_call_peer") ?: return@let null
        IncomingCallIntent(callId, peerId, intent.getStringExtra("incoming_call_name") ?: "Входящий звонок", intent.getStringExtra("incoming_call_action"))
    }
    override fun onResume() {
        super.onResume()
        getSharedPreferences("salom_local", MODE_PRIVATE).edit().putBoolean("app_visible", true).apply()
        PushNotifications.sync(this)
        updater.onResume()
    }
    override fun onStop() {
        getSharedPreferences("salom_local", MODE_PRIVATE).edit().putBoolean("app_visible", false).apply()
        super.onStop()
    }
}

internal class Store(context: Context) {
    val prefs = context.getSharedPreferences("salom_local", Context.MODE_PRIVATE)
    private val identity = context.getSharedPreferences("salom_directory", Context.MODE_PRIVATE)
    init {
        val owner = identity.getString("device_id", "").orEmpty()
        if (owner.isNotEmpty() && !prefs.getBoolean("notes_scoped", false)) {
            val legacy = prefs.getString("messages_saved", null)
            prefs.edit().apply { if (legacy != null) putString("messages_saved_$owner", legacy); remove("messages_saved"); putBoolean("notes_scoped", true) }.commit()
        }
    }
    fun text(key: String, fallback: String = "") = prefs.getString(key, fallback) ?: fallback
    fun save(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    fun messages(id: String): List<Message> {
        val raw = text("messages_${id}_${identity.getString("device_id", "")}")
        if (raw.isEmpty()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { val item = array.getJSONObject(it); Message(item.getString("text"), item.getBoolean("mine"), item.optString("time", ""), attachment = item.optJSONObject("attachment")?.let { Attachment.from(it) }) }.filter { it.mine }
        }.getOrDefault(emptyList())
    }
    fun clearMessages(id: String) {
        check(prefs.edit().remove("messages_${id}_${identity.getString("device_id", "")}").commit()) { "Не удалось очистить сообщения" }
    }
    fun saveMessages(id: String, messages: List<Message>) {
        val array = JSONArray()
        messages.forEach { array.put(JSONObject().put("text", it.text).put("mine", it.mine).put("time", it.time).apply { it.attachment?.let { attachment -> put("attachment", attachment.json()) } }) }
        save("messages_${id}_${identity.getString("device_id", "")}", array.toString())
    }
}

@Composable
private fun SalomApp(context: Context, updater: AppUpdater, notificationPeer: String?, incomingCall: IncomingCallIntent?, onNotificationHandled: () -> Unit, onCallIntentHandled: () -> Unit) {
    var credentialVersion by remember { mutableIntStateOf(0) }
    val directory = remember(credentialVersion) { DirectoryClient(context) }
    val store = remember { Store(context) }
    val appScope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var securingAccount by remember { mutableStateOf(false) }
    var directoryError by remember { mutableStateOf<String?>(null) }
    var signedIn by remember { mutableStateOf(store.text("signed_in") == "yes") }
    var dark by remember { mutableStateOf(store.text("dark") == "yes") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var activeChat by remember { mutableStateOf<Person?>(null) }
    var chatWallpaper by rememberSaveable { mutableStateOf(store.text("chat_wallpaper", "midnight")) }
    var profileVersion by remember { mutableIntStateOf(0) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { PushNotifications.sync(context) }
    LaunchedEffect(signedIn) {
        PushNotifications.sync(context)
        if (signedIn && android.os.Build.VERSION.SDK_INT >= 33 && store.text("notification_permission_asked") != "yes") {
            store.save("notification_permission_asked", "yes")
            permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(notificationPeer, signedIn) {
        if (signedIn && notificationPeer != null) {
            try {
                val account = directory.peer(notificationPeer)
                tab = 0
                activeChat = Person(account.id, account.name, account.name.take(1).uppercase(), Yellow, "", account.username, account.avatarUrl)
            } catch (exception: DirectoryException) {
                android.widget.Toast.makeText(context, exception.message, android.widget.Toast.LENGTH_LONG).show()
            }
            onNotificationHandled()
        }
    }
    LaunchedEffect(incomingCall, signedIn) {
        incomingCall?.let { call -> activeChat = Person(call.peerId, call.peerName, call.peerName.take(1).uppercase(), Yellow, "Звонок", avatarUrl = "") }
    }
    val accounts = remember(profileVersion, signedIn) { listOf(SalomAccount("self", store.text("name"), store.text("username"), store.text("phone"), is_active = signedIn)) }
    LaunchedEffect(profileVersion, signedIn) {
        if (signedIn) {
            try { directory.syncLocal(); directoryError = null }
            catch (exception: DirectoryException) {
                directoryError = exception.message
                if (exception.status == 401 && store.text("account_verified") == "yes") { signedIn = false; activeChat = null }
            }
        }
    }
    LaunchedEffect(signedIn, directory) {
        if (signedIn) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    try { directory.heartbeat() } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { }
                    delay(20_000)
                }
            }
        }
    }
    LaunchedEffect(signedIn, directory) {
        if (signedIn) runCatching { directory.missedCalls() }.getOrNull()?.let { count ->
            if (count > 0) android.widget.Toast.makeText(context,
                if (count == 1) "Пропущенный звонок" else "Пропущенные звонки ($count)", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val openAccount: (SalomAccount) -> Unit = { account -> activeChat = if (account.id == "self") savedPerson else Person(account.id, account.name, account.name.take(1).uppercase(), Yellow, "", account.username, account.avatarUrl) }
    val scheme = if (dark) darkColorScheme(primary = Yellow, onPrimary = Ink, background = Color(0xFF171816), surface = Color(0xFF232420), secondaryContainer = Color(0xFF494125))
    else lightColorScheme(primary = Yellow, onPrimary = Ink, background = Soft, surface = Color.White, onSurface = Ink, secondaryContainer = Color(0xFFFFF0B2))
    SideEffect {
        store.prefs.edit().putBoolean("app_visible", signedIn && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)).putString("active_peer_id", activeChat?.id?.takeUnless { it == "saved" }).apply()
        (context as? ComponentActivity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = activeChat == null && !dark
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
                isAppearanceLightNavigationBars = activeChat == null && !dark
            }
        }
    }
    MaterialTheme(colorScheme = scheme, typography = Typography()) {
        Surface(Modifier.fillMaxSize().then(if (activeChat != null) Modifier.paint(androidx.compose.ui.res.painterResource(chatWallpaperResource(chatWallpaper)), contentScale = androidx.compose.ui.layout.ContentScale.Crop) else Modifier), color = if (activeChat != null) Color(0xFF101923) else scheme.background) {
            Box(Modifier.fillMaxSize()
                .then(if (activeChat != null) Modifier.background(if (dark) Color(0xFF101923) else Color(0xFFF2F0E6)) else Modifier)
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)).imePadding()) {
            if (!signedIn || securingAccount) {
                AuthScreen(context, directory, securingAccount, onSuccess = {
                    credentialVersion++; profileVersion++; directoryError = null; activeChat = null; signedIn = true; securingAccount = false
                }, onCancel = if (securingAccount) ({ securingAccount = false }) else null)
            } else if (activeChat != null) {
                BackHandler { activeChat = null }
                key(activeChat!!.id) {
                    Conversation(activeChat!!, store, directory, chatWallpaper, incomingCall, onCallIntentHandled, { value -> chatWallpaper = value; store.save("chat_wallpaper", value) }) { activeChat = null }
                }
            } else {
                Scaffold(containerColor = scheme.background, contentWindowInsets = WindowInsets(0, 0, 0, 0), bottomBar = {
                    Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(scheme.surface).selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                        listOf("Чаты", "Контакты", "Настройки", "Профиль").forEachIndexed { index, title ->
                            Column(Modifier.weight(1f).heightIn(min = 56.dp).selectable(selected = tab == index, role = Role.Tab, onClick = { tab = index }).padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Box(Modifier.size(width = 42.dp, height = 27.dp).clip(RoundedCornerShape(16.dp)).background(if (tab == index) Yellow else Color.Transparent), contentAlignment = Alignment.Center) {
                                    NavIcon(index, if (tab == index) Ink else scheme.onSurfaceVariant, Modifier.size(22.dp))
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(title, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, color = if (tab == index) scheme.onSurface else scheme.onSurfaceVariant)
                            }
                        }
                    }
                }) { padding ->
                    Crossfade(targetState = tab, animationSpec = tween(140), modifier = Modifier.padding(padding), label = "navigation") { page ->
                        when (page) {
                            0 -> Column {
                                val updateStatus by updater.status.collectAsState()
                                if (updateStatus is UpdateStatus.Available || updateStatus is UpdateStatus.Ready) {
                                    TextButton(onClick = { tab = 2 }, modifier = Modifier.fillMaxWidth()) { Text("Доступно обновление Салома →") }
                                }
                                Box(Modifier.weight(1f)) { Chats(context, store, accounts, directory, openAccount, { activeChat = it }, { tab = 1 }, { tab = 3 }) }
                            }
                            1 -> PhoneContacts(context, accounts, openAccount)
                            2 -> Settings(updater, dark, { dark = it; store.save("dark", if (it) "yes" else "no") }, {
                                appScope.launch { AuthClient(context, directory).logout(); activeChat = null; signedIn = false; securingAccount = false; tab = 0 }
                            })
                            else -> Profile(store, profileVersion, directory, directoryError, { securingAccount = true }) { directoryError = null; profileVersion++ }
                        }
                    }
                }
            }
        }
    }
  }
}

@Composable
private fun Header(title: String, subtitle: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

@Composable
private fun Avatar(person: Person, size: Int = 46) {
    var photo by remember(person.avatarUrl) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(person.avatarUrl) {
        photo = if(person.avatarUrl.startsWith("https://lh3.googleusercontent.com/") || person.avatarUrl.startsWith("https://googleusercontent.com/"))
            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { java.net.URL(person.avatarUrl).openConnection().apply { connectTimeout=4000; readTimeout=4000 }.getInputStream().use { android.graphics.BitmapFactory.decodeStream(it) } } }.getOrNull()
        else null
    }
    Box(Modifier.size(size.dp).clip(CircleShape).background(person.color), contentAlignment = Alignment.Center) {
        if(photo!=null) androidx.compose.foundation.Image(photo!!.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=androidx.compose.ui.layout.ContentScale.Crop)
        else Text(person.initials, color = Ink, fontSize = (size * .37).sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Chats(context: Context, store: Store, accounts: List<SalomAccount>, directory: DirectoryClient, onOpenAccount: (SalomAccount) -> Unit, onOpen: (Person) -> Unit, onNew: () -> Unit, onProfile: () -> Unit) {
    var search by remember { mutableStateOf("") }
    var remoteChats by remember { mutableStateOf<List<ConversationPreview>>(emptyList()) }
    var inboxError by remember { mutableStateOf<String?>(null) }
    val localCache = remember(context) { LocalChatCache(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(directory) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            remoteChats = localCache.conversations()
            while (isActive) {
                try {
                    val latest = directory.conversations()
                    if (latest != remoteChats) {
                        remoteChats = latest
                        localCache.replaceConversations(latest)
                    }
                    inboxError = null
                }
                catch (exception: CancellationException) { throw exception }
                catch (exception: DirectoryException) { inboxError = exception.message }
                delay(3000)
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column {
            Header("Салом") { Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onProfile), contentAlignment = Alignment.Center) { Box(Modifier.size(32.dp).clip(CircleShape).background(Yellow), contentAlignment = Alignment.Center) { Text(store.text("name", "С").take(1).uppercase(), fontSize = 14.sp, color = Ink, fontWeight = FontWeight.Bold) } } }
            CompactSearch(search, { search = it }, "Имя, username или номер")
            Spacer(Modifier.height(6.dp))
            val preview = remember { store.messages("saved").lastOrNull()?.let { it.text.ifBlank { it.attachment?.name.orEmpty() } } ?: savedPerson.preview }
            val visible = listOf(savedPerson)
            if (search.isNotBlank()) SmartPeopleResults(context, search, accounts, directory, onOpenAccount, onProfile)
            else LazyColumn(contentPadding = PaddingValues(bottom = 90.dp)) {
                inboxError?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) } }
                items(visible, key = { it.id }, contentType = { "chat" }) { person ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { onOpen(person) }.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(person)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(person.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(preview, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                items(remoteChats, key = { it.account.id }, contentType = { "remote_chat" }) { conversation ->
                    val account = conversation.account
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { onOpenAccount(account) }.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(Person(account.id, account.name, account.name.take(1).uppercase(), Color(0xFFFFE9A6), "", account.username, account.avatarUrl))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(account.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(conversation.lastText, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
                if (search.isEmpty() && remoteChats.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(60.dp).clip(RoundedCornerShape(20.dp)).background(Yellow.copy(alpha = .18f)), contentAlignment = Alignment.Center) { NavIcon(0, MaterialTheme.colorScheme.onSurface, Modifier.size(27.dp)) }
                        Spacer(Modifier.height(16.dp))
                        Text("Начни с «Салом»", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        Text("Твои близкие уже есть в телефонной книге", fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
                        TextButton(onClick = onNew) { Text("Открыть контакты", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
        FloatingActionButton(onClick = onNew, containerColor = Yellow, contentColor = Ink, shape = RoundedCornerShape(18.dp), modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).size(48.dp)) { Text("+", fontSize = 28.sp) }
    }
}

@Composable
private fun Profile(store: Store, version: Int, directory: DirectoryClient, syncError: String?, onVerify: () -> Unit, onChanged: () -> Unit) {
    var edit by remember { mutableStateOf(false) }
    val name = remember(version) { store.text("name", "Твой профиль") }
    val about = remember(version) { store.text("about", "Салом! Я на связи 💛") }
    val username = remember(version) { store.text("username") }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(16.dp))
        Avatar(Person("me", name, name.take(1).uppercase(), Yellow, "", avatarUrl=store.text("avatar_url")), 76)
        Spacer(Modifier.height(12.dp))
        Text(name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("Мой профиль", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (syncError != null) Text(syncError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
        Spacer(Modifier.height(12.dp))
        Button(onClick = { edit = true }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Редактировать профиль", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (store.text("phone").isNotEmpty()) Detail(store.text("phone"), "Телефон")
                if (store.text("email").isNotEmpty()) Detail(store.text("email"), "Email")
                if (username.isNotEmpty()) Detail("@$username", "Имя пользователя")
                Detail(about, "О себе")
            }
        }
        AccountSecurityCard(directory, onVerify)
        Spacer(Modifier.height(18.dp))
        Text("Всё начинается с «Салом»", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (edit) {
        var newName by remember { mutableStateOf(name) }; var newAbout by remember { mutableStateOf(about) }; var newUsername by remember { mutableStateOf(username) }
        val usernameValid = newUsername.isEmpty() || newUsername.matches(Regex("[a-zA-Z][a-zA-Z0-9_]{3,23}"))
        var saving by remember { mutableStateOf(false) }
        var saveError by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { if (!saving) edit = false }, title = { Text("Твой профиль") }, text = { Column {
            OutlinedTextField(newName, { newName = it.take(40) }, label = { Text("Имя") }, singleLine = true)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(newUsername, { newUsername = it.removePrefix("@").take(24) }, label = { Text("@username") }, singleLine = true, isError = !usernameValid, supportingText = { Text("4–24: латинские буквы, цифры и _") })
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(newAbout, { newAbout = it.take(160) }, label = { Text("О себе") }, maxLines = 3)
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            Text("Имя и username доступны другим пользователям Салом", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        } }, confirmButton = { TextButton(onClick = {
            saving = true; saveError = null
            val saveName = newName.trim(); val saveUsername = newUsername; val saveAbout = newAbout.trim()
            scope.launch {
                try {
                    directory.publish(saveName, saveUsername)
                    store.save("name", saveName); store.save("about", saveAbout); store.save("username", saveUsername)
                    onChanged(); edit = false
                } catch (exception: DirectoryException) { saveError = exception.message }
                finally { saving = false }
            }
        }, enabled = !saving && newName.isNotBlank() && usernameValid) { Text(if (saving) "Сохраняем…" else "Сохранить", color = MaterialTheme.colorScheme.onSurface) } }, dismissButton = { TextButton(onClick = { if (!saving) edit = false }, enabled = !saving) { Text("Отмена", color = MaterialTheme.colorScheme.onSurface) } })
    }
}

@Composable
private fun Detail(value: String, label: String) { Column { Text(value, fontSize = 14.sp); Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp)) } }

@Composable
private fun Settings(updater: AppUpdater, dark: Boolean, onDark: (Boolean) -> Unit, onLogout: () -> Unit) {
    val notificationSettingsContext = androidx.compose.ui.platform.LocalContext.current
    var confirmLogout by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("Настройки", "Сделай Салом своим")
        Card(Modifier.padding(horizontal = 14.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Тёмная тема", fontWeight = FontWeight.Medium); Text("Для вечерних разговоров", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked = dark, onCheckedChange = onDark, colors = SwitchDefaults.colors(checkedTrackColor = Yellow, checkedThumbColor = Ink)) }
                HorizontalDivider(Modifier.padding(vertical = 18.dp))
                Detail("На этом телефоне", "Хранение заметок")
                Spacer(Modifier.height(20.dp))
                Detail(updater.installedDisplayVersion, "Версия Салом")
                TextButton(onClick = {
                    val settingsContext = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, "com.mukimov.salom")
                    // App notification settings are available from Android 8; older devices use app details.
                    if (android.os.Build.VERSION.SDK_INT >= 26) settingsContext.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    else { settingsContext.action = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS; settingsContext.data = android.net.Uri.parse("package:com.mukimov.salom") }
                    notificationSettingsContext.startActivity(settingsContext)
                }) { Text("Настроить уведомления") }
            }
        }
        UpdateCard(updater)
        TextButton(onClick = { confirmLogout = true }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Выйти из профиля", color = MaterialTheme.colorScheme.error) }
    }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("Выйти из профиля?") }, text = { Text("Локальные сообщения останутся на этом телефоне.") }, confirmButton = { TextButton(onClick = { confirmLogout = false; onLogout() }) { Text("Выйти", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Отмена", color = MaterialTheme.colorScheme.onSurface) } })
}

@Composable
private fun NavIcon(index: Int, color: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val w = size.width; val h = size.height; val stroke = Stroke(width = w * .075f)
        when (index) {
            0 -> { drawRoundRect(color, Offset(w*.12f,h*.14f), Size(w*.76f,h*.57f), CornerRadius(w*.18f), style = stroke); drawPath(Path().apply { moveTo(w*.25f,h*.69f); lineTo(w*.23f,h*.88f); lineTo(w*.48f,h*.72f) }, color, style = stroke) }
            1 -> { drawCircle(color,w*.16f,Offset(w*.37f,h*.3f),style=stroke); drawArc(color,180f,180f,false,Offset(w*.08f,h*.52f),Size(w*.6f,h*.6f),style=stroke); drawCircle(color,w*.12f,Offset(w*.77f,h*.36f),style=stroke); drawLine(color,Offset(w*.77f,h*.62f),Offset(w*.91f,h*.83f),w*.075f) }
            2 -> { drawCircle(color,w*.29f,center,style=stroke); drawCircle(color,w*.09f,center,style=stroke); for (i in 0..7) { val a = i * Math.PI / 4; drawLine(color,Offset(w*.5f+(kotlin.math.cos(a)*w*.32f).toFloat(),h*.5f+(kotlin.math.sin(a)*h*.32f).toFloat()),Offset(w*.5f+(kotlin.math.cos(a)*w*.43f).toFloat(),h*.5f+(kotlin.math.sin(a)*h*.43f).toFloat()),w*.09f) } }
            else -> { drawCircle(color,w*.18f,Offset(w*.5f,h*.3f),style=stroke); drawArc(color,180f,180f,false,Offset(w*.16f,h*.57f),Size(w*.68f,h*.55f),style=stroke) }
        }
    }
}
