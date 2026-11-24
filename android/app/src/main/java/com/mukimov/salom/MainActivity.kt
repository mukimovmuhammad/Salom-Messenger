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
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.launch

internal val Yellow = Color(0xFFFFC400)
internal val Ink = Color(0xFF25241F)
private val Soft = Color(0xFFF5F5F3)
internal data class Person(val id: String, val name: String, val initials: String, val color: Color, val preview: String, val username: String = "", val avatarUrl: String = "")
internal data class Message(val text: String, val mine: Boolean, val time: String = "", val remoteId: Long = 0, val attachment: Attachment? = null, val receipt: String = "sent", val replyToId: Long? = null, val replyAuthor: String? = null, val replyPreview: String? = null, val edited: Boolean = false, val locationId: String? = null, val clientId: String? = null, val myReaction: String? = null, val peerReaction: String? = null)
internal data class IncomingCallIntent(val callId: String, val peerId: String, val peerName: String, val action: String? = null, val video: Boolean = false)
private val savedPerson = Person("saved", "Избранное", "★", Yellow, "Сообщения и заметки для себя")

class MainActivity : ComponentActivity() {
    internal companion object {
        @Volatile var visible = false
            private set
    }
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
        IncomingCallIntent(callId, peerId, intent.getStringExtra("incoming_call_name") ?: "Входящий звонок", intent.getStringExtra("incoming_call_action"), intent.getBooleanExtra("incoming_call_video", false))
    }
    override fun onStart() {
        super.onStart()
        visible = true
    }
    override fun onResume() {
        super.onResume()
        PushNotifications.sync(this)
        updater.onResume()
    }
    override fun onStop() {
        visible = false
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
    var settingsPage by rememberSaveable { mutableStateOf(SettingsPage.MENU) }
    var settingsReturnTab by rememberSaveable { mutableIntStateOf(0) }
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
        incomingCall?.let { call ->
            if (activeChat?.id != call.peerId) {
                activeChat = Person(call.peerId, call.peerName, call.peerName.take(1).uppercase(), Yellow, "Звонок", avatarUrl = "")
            }
        }
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
    val changeTheme: (Boolean) -> Unit = { dark = it; store.save("dark", if (it) "yes" else "no") }
    val logout: () -> Unit = {
        appScope.launch { AuthClient(context, directory).logout(); activeChat = null; signedIn = false; securingAccount = false; tab = 0 }
    }
    val scheme = if (dark) darkColorScheme(primary = Yellow, onPrimary = Ink, background = Color(0xFF171816), surface = Color(0xFF232420), secondaryContainer = Color(0xFF494125))
    else lightColorScheme(primary = Yellow, onPrimary = Ink, background = Soft, surface = Color.White, onSurface = Ink, secondaryContainer = Color(0xFFFFF0B2))
    SideEffect {
        store.prefs.edit().putString("active_peer_id", activeChat?.id?.takeUnless { it == "saved" }).apply()
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
                            Column(Modifier.weight(1f).heightIn(min = 56.dp).selectable(selected = tab == index, role = Role.Tab, onClick = {
                                if (index == 2) {
                                    settingsPage = SettingsPage.MENU
                                    if (tab != 2) settingsReturnTab = tab
                                }
                                tab = index
                            }).padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
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
                                    TextButton(onClick = { settingsPage = SettingsPage.UPDATES; settingsReturnTab = 0; tab = 2 },
                                        modifier = Modifier.fillMaxWidth()) { Text("Доступно обновление Салома →") }
                                }
                                Box(Modifier.weight(1f)) { Chats(context, store, accounts, directory, openAccount, { activeChat = it }, { tab = 1 }, { tab = 3 }) }
                            }
                            1 -> PhoneContacts(context, accounts, openAccount)
                            2 -> Settings(updater, store, profileVersion, dark, changeTheme, settingsPage,
                                onPage = { settingsPage = it }, onExit = { tab = settingsReturnTab }, onProfile = { tab = 3 },
                                active = tab == 2)
                            else -> Profile(store, profileVersion, directory, directoryError, dark, changeTheme,
                                onSettings = { settingsPage = SettingsPage.MENU; settingsReturnTab = 3; tab = 2 },
                                onLogout = logout, onVerify = { securingAccount = true },
                                onChanged = { directoryError = null; profileVersion++ })
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
internal fun Avatar(person: Person, size: Int = 46) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val photos = remember(context) { SalomPhotos(context) }
    var photo by remember(person.avatarUrl) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(person.avatarUrl) {
        photo = try { photos.avatar(person.avatarUrl) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
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
    val localCache = remember(context, directory.ownId) { LocalChatCache(context, directory.ownId) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(directory, localCache) {
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
                        if (conversation.unreadCount > 0) Box(Modifier.padding(start = 8.dp).clip(CircleShape).background(Yellow).defaultMinSize(minWidth = 22.dp, minHeight = 22.dp).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
                            Text(if (conversation.unreadCount > 99) "99+" else conversation.unreadCount.toString(), color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
private fun Profile(store: Store, version: Int, directory: DirectoryClient, syncError: String?, dark: Boolean,
    onDark: (Boolean) -> Unit, onSettings: () -> Unit, onLogout: () -> Unit, onVerify: () -> Unit, onChanged: () -> Unit) {
    var edit by remember { mutableStateOf(false) }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    val name = remember(version) { store.text("name", "Твой профиль") }
    val about = remember(version) { store.text("about") }
    val username = remember(version) { store.text("username") }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val photos = remember(context) { SalomPhotos(context) }
    var avatarBusy by remember { mutableStateOf(false) }
    var avatarMessage by remember { mutableStateOf<String?>(null) }
    var avatarDraft by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var showAppearance by rememberSaveable { mutableStateOf(false) }
    var showAccount by rememberSaveable { mutableStateOf(false) }
    var chosenCover by remember(dark) {
        val name = store.text(if (dark) "profile_cover_dark" else "profile_cover_light")
        mutableStateOf(ProfileCover.entries.firstOrNull { it.name == name } ?: ProfileCover.HONEY)
    }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) scope.launch {
            avatarBusy = true
            avatarMessage = null
            try {
                avatarDraft = photos.avatarSource(uri)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { avatarMessage = "Не удалось открыть выбранное фото." }
            finally { avatarBusy = false }
        }
    }
    val palette = MaterialTheme.colorScheme
    val avatarUrl = remember(version) { store.text("avatar_url") }
    val phone = remember(version) { store.text("phone") }
    val email = remember(version) { store.text("email") }
    val choosePhoto = {
        picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(195.dp)) {
            Canvas(Modifier.fillMaxWidth().height(144.dp).background(chosenCover.brush(dark))) {
                val tint = Color.White.copy(alpha = if (dark) .07f else .23f)
                drawCircle(tint, size.height * .72f, Offset(size.width * .84f, size.height * .08f))
                drawCircle(Color.White.copy(alpha = .24f), size.height * .88f,
                    Offset(size.width * .84f, size.height * .08f), style = Stroke(1.dp.toPx()))
            }
            Box(
                Modifier.align(Alignment.BottomCenter).size(110.dp).shadow(9.dp, CircleShape)
                    .clip(CircleShape).background(palette.surface)
                    .clickable(enabled = !avatarBusy, role = Role.Button, onClick = choosePhoto)
                    .semantics { contentDescription = "Сменить фото профиля" },
                contentAlignment = Alignment.Center
            ) {
                Avatar(Person("me", name, name.trim().take(1).uppercase().ifEmpty { "С" }, Yellow, "", avatarUrl = avatarUrl), 102)
                if (avatarBusy) CircularProgressIndicator(Modifier.size(28.dp), color = palette.onSurface, strokeWidth = 3.dp)
            }
        }
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(14.dp))
            Text(name, style = MaterialTheme.typography.headlineMedium, fontSize = 30.sp, lineHeight = 36.sp,
                letterSpacing = (-.55).sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (username.isNotBlank()) {
                Text("@$username", modifier = Modifier.padding(top = 4.dp),
                    fontSize = 14.sp, color = palette.onSurfaceVariant, fontWeight = FontWeight.Medium)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { edit = true }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(13.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) { Text("Редактировать", textAlign = TextAlign.Center) }
                FilledTonalButton(onClick = choosePhoto, enabled = !avatarBusy, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(13.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) {
                    Text(if (avatarBusy) "Загрузка…" else "Сменить фото", textAlign = TextAlign.Center)
                }
            }
            avatarMessage?.let { Text(it, color = palette.onSurfaceVariant, fontSize = 12.sp, textAlign = TextAlign.Center) }
            syncError?.let { Text(it, color = palette.error, fontSize = 12.sp, modifier = Modifier.padding(8.dp)) }

            val details = listOfNotNull(
                phone.takeIf(String::isNotBlank)?.let { "Телефон" to it },
                email.takeIf(String::isNotBlank)?.let { "Почта" to it },
                about.takeIf(String::isNotBlank)?.let { "О себе" to it }
            )
            if (details.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(20.dp), color = palette.surface) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
                        details.forEachIndexed { index, (label, value) ->
                            if (index != 0) HorizontalDivider(color = palette.outlineVariant.copy(alpha = .55f))
                            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(label, Modifier.weight(.36f), color = palette.onSurfaceVariant, fontSize = 14.sp)
                                Text(value, Modifier.weight(.64f), fontSize = 14.sp, lineHeight = 20.sp,
                                    textAlign = TextAlign.End, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Surface(shape = RoundedCornerShape(20.dp), color = palette.surface) {
                Column(Modifier.fillMaxWidth()) {
                    ProfileAction("Тёмная тема", "moon", checked = dark, onClick = { onDark(!dark) }) {
                        Switch(dark, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = Yellow, checkedThumbColor = Ink))
                    }
                    ProfileDivider()
                    ProfileAction("Оформление профиля", "photo", onClick = { showAppearance = true })
                    ProfileDivider()
                    ProfileAction("Аккаунт", "person", onClick = { showAccount = true })
                    ProfileDivider()
                    ProfileAction("Настройки", "settings", onClick = onSettings)
                    ProfileDivider()
                    ProfileAction("Выйти из профиля", "exit", color = palette.error, onClick = { confirmLogout = true })
                }
            }
            Spacer(Modifier.height(22.dp))
        }
    }
    if (showAppearance) ProfileCoverPicker(dark, chosenCover, onClose = { showAppearance = false }) { cover ->
        chosenCover = cover
        store.save(if (dark) "profile_cover_dark" else "profile_cover_light", cover.name)
    }
    if (showAccount) ProfileAccountDialog(directory, onClose = { showAccount = false }, onVerify = {
        showAccount = false
        onVerify()
    })
    avatarDraft?.let { bitmap ->
        AvatarCropDialog(bitmap, onDismiss = { avatarDraft = null }) { crop ->
            avatarDraft = null
            avatarBusy = true
            scope.launch {
                try {
                    val jpeg = photos.avatarJpeg(bitmap, crop)
                    val url = directory.uploadAvatar(jpeg)
                    store.save("avatar_url", url)
                    onChanged()
                    try {
                        photos.rememberAvatar(url, jpeg)
                        if (android.os.Build.VERSION.SDK_INT >= 29) photos.saveAvatar(url)
                        avatarMessage = "Фото профиля обновлено"
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { avatarMessage = "Фото профиля обновлено. Сохранить копию в галерее не удалось." }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { avatarMessage = "Не удалось сменить фото профиля. Проверь подключение." }
                finally { avatarBusy = false }
            }
        }
    }
    if (confirmLogout) LogoutConfirmation(onDismiss = { confirmLogout = false }, onLogout = onLogout)
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
private fun ProfileAction(
    title: String,
    icon: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    checked: Boolean? = null,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    val action = if (checked == null) Modifier.clickable(role = Role.Button, onClick = onClick)
        else Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = { onClick() })
    Row(
        Modifier.fillMaxWidth().heightIn(min = 58.dp).then(action).padding(horizontal = 16.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProfileIcon(icon, color)
        Text(title, Modifier.weight(1f), color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp)
        if (trailing != null) trailing()
        else ProfileIcon("chevron", MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProfileDivider() = HorizontalDivider(
    Modifier.padding(horizontal = 16.dp),
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)
)

@Composable
private fun ProfileIcon(icon: String, color: Color) {
    if (icon == "person" || icon == "settings") {
        NavIcon(if (icon == "person") 3 else 2, color)
        return
    }
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24
        val stroke = Stroke(1.75f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        val shape = Path().apply {
            fun point(x: Float, y: Float) = lineTo(x * u, y * u)
            fun start(x: Float, y: Float) = moveTo(x * u, y * u)
            when (icon) {
                "moon" -> {
                    start(20.5f, 15f)
                    cubicTo(14f * u, 17.5f * u, 6.5f * u, 10f * u, 9f * u, 3.5f * u)
                    cubicTo(1f * u, 6f * u, 1f * u, 16f * u, 7f * u, 20f * u)
                    cubicTo(12f * u, 23f * u, 18f * u, 20f * u, 20.5f * u, 15f * u)
                    close()
                }
                "exit" -> {
                    start(10f, 4f); point(4f, 4f); point(4f, 20f); point(10f, 20f)
                    start(9f, 12f); point(21f, 12f)
                    start(16f, 7f); point(21f, 12f); point(16f, 17f)
                }
                "back" -> { start(15f, 5f); point(8f, 12f); point(15f, 19f) }
                "update" -> {
                    start(12f, 3f); point(12f, 15f)
                    start(7f, 10f); point(12f, 15f); point(17f, 10f)
                    start(4f, 16f); point(4f, 20f); point(20f, 20f); point(20f, 16f)
                }
                "bell" -> {
                    start(4f, 18f); point(6f, 15f); point(6f, 10f)
                    cubicTo(6f * u, 2.5f * u, 18f * u, 2.5f * u, 18f * u, 10f * u)
                    point(18f, 15f); point(20f, 18f); close()
                    start(10f, 21f); point(14f, 21f)
                }
                "info" -> {
                    addOval(androidx.compose.ui.geometry.Rect(3f * u, 3f * u, 21f * u, 21f * u))
                    start(12f, 11f); point(12f, 16f)
                    start(12f, 8f); point(12.1f, 8f)
                }
                "photo" -> {
                    start(4f, 5f); point(20f, 5f); point(20f, 19f); point(4f, 19f); close()
                    start(4f, 16f); point(9f, 11f); point(13f, 15f); point(16f, 12f); point(20f, 16f)
                }
                else -> { start(9f, 7f); point(14f, 12f); point(9f, 17f) }
            }
        }
        drawPath(shape, color, style = stroke)
    }
}

@Composable
private fun LogoutConfirmation(onDismiss: () -> Unit, onLogout: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Выйти из профиля?") },
        text = { Text("Локальные сообщения останутся на этом телефоне.") },
        confirmButton = { TextButton(onClick = { onDismiss(); onLogout() }) { Text("Выйти", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = MaterialTheme.colorScheme.onSurface) } }
    )
}

private enum class SettingsPage(val title: String) {
    MENU("Настройки"), UPDATES("Обновить приложение"),
    NOTIFICATIONS("Уведомления"), ABOUT("О приложении")
}

@Composable
private fun Settings(
    updater: AppUpdater, store: Store, profileVersion: Int, dark: Boolean, onDark: (Boolean) -> Unit,
    page: SettingsPage, onPage: (SettingsPage) -> Unit, onExit: () -> Unit, onProfile: () -> Unit,
    active: Boolean
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val colors = MaterialTheme.colorScheme
    val photos = remember(context) { SalomPhotos(context) }
    var autoSave by remember { mutableStateOf(photos.autoSave) }
    var privatePreview by remember {
        mutableStateOf(store.prefs.getBoolean("private_notification_preview", false))
    }
    val goBack = { if (page == SettingsPage.MENU) onExit() else onPage(SettingsPage.MENU) }
    BackHandler(enabled = active, onBack = goBack)
    val updateStatus by updater.status.collectAsState()

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().background(colors.surface).heightIn(min = 56.dp).padding(vertical = 4.dp)) {
            Text(page.title, Modifier.align(Alignment.Center).padding(horizontal = 60.dp),
                textAlign = TextAlign.Center, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = goBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp)
                .semantics { contentDescription = "Назад" }) {
                ProfileIcon("back", colors.onSurface)
            }
        }
        key(page) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (page) {
                    SettingsPage.MENU -> {
                        val person = remember(profileVersion) {
                            val name = store.text("name").ifBlank { "Мой профиль" }
                            Person("settings-profile", name, name.take(1).uppercase(), Yellow, "",
                                username = store.text("username").trim().trimStart('@'), avatarUrl = store.text("avatar_url"))
                        }
                        Row(Modifier.fillMaxWidth().background(colors.surface)
                            .clickable(role = Role.Button, onClickLabel = "Открыть профиль", onClick = onProfile)
                            .padding(horizontal = 18.dp, vertical = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(person, 54)
                            Column(Modifier.weight(1f)) {
                                Text(person.name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(person.username.takeIf(String::isNotEmpty)?.let { "@$it" } ?: "Мой профиль",
                                    fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            ProfileIcon("chevron", colors.onSurfaceVariant)
                        }
                        SettingsGroup {
                            ProfileAction(SettingsPage.NOTIFICATIONS.title, "bell", onClick = { onPage(SettingsPage.NOTIFICATIONS) })
                            ProfileDivider()
                            SettingsSwitch("Тёмная тема", "moon", dark, onDark)
                            if (android.os.Build.VERSION.SDK_INT >= 29) {
                                ProfileDivider()
                                SettingsSwitch("Сохранять свои фото из чатов", "photo", autoSave) {
                                    autoSave = it
                                    photos.autoSave = it
                                }
                            }
                        }
                        SettingsGroup {
                            val accent = when (updateStatus) {
                                is UpdateStatus.Available, is UpdateStatus.Ready -> if (dark) Yellow else Color(0xFF785600)
                                else -> colors.onSurface
                            }
                            ProfileAction(SettingsPage.UPDATES.title, "update", color = accent, onClick = { onPage(SettingsPage.UPDATES) })
                            ProfileDivider()
                            ProfileAction(SettingsPage.ABOUT.title, "info", onClick = { onPage(SettingsPage.ABOUT) })
                        }
                    }
                    SettingsPage.NOTIFICATIONS -> SettingsGroup {
                        SettingsSwitch("Скрывать текст уведомлений", "bell", privatePreview) {
                            privatePreview = it
                            store.prefs.edit().putBoolean("private_notification_preview", it).apply()
                        }
                        ProfileDivider()
                        ProfileAction("Настройки уведомлений Android", "settings", onClick = {
                            val intent = if (android.os.Build.VERSION.SDK_INT >= 26) {
                                android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                            } else {
                                android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:${context.packageName}"))
                            }
                            context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        })
                    }
                    SettingsPage.UPDATES -> UpdateCard(updater)
                    SettingsPage.ABOUT -> SettingsGroup {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("Салом", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                            Text("Версия ${updater.installedDisplayVersion}", fontSize = 14.sp, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface), content = content)
}

@Composable
private fun SettingsSwitch(title: String, icon: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ProfileAction(title, icon, checked = checked, onClick = { onCheckedChange(!checked) }, trailing = {
        Switch(checked = checked, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = Yellow, checkedThumbColor = Ink))
    })
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
