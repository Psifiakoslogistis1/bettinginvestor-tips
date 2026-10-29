package gr.bettinginvestor.tips

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gr.bettinginvestor.tips.BuildConfig
import gr.bettinginvestor.tips.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
// MartingaleStore via wildcard
import gr.bettinginvestor.tips.ui.BiColors
import gr.bettinginvestor.tips.ui.MartingalePage
import gr.bettinginvestor.tips.ui.StarOfDayBadge
import gr.bettinginvestor.tips.ui.TipsTheme
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LocalDemoStore.ensureLoaded(this)
        MartingaleStore.loadSeed(this)
        val prefs = getSharedPreferences("tips_auth", Context.MODE_PRIVATE)
        if (prefs.getBoolean("paid_unlocked", false)) LocalDemoStore.unlockPaid()
        // Refresh admin role from email (fixes stale role=user cache)
        refreshAdminRole(prefs)
        setContent { TipsTheme { AppRoot(prefs) } }
    }

    companion object {
        fun refreshAdminRole(prefs: SharedPreferences) {
            if (!prefs.getBoolean("logged_in", false)) return
            val email = prefs.getString("email", "") ?: ""
            val role = DemoAuth.roleForEmail(email)
            if (prefs.getString("role", "user") != role) {
                prefs.edit().putString("role", role).apply()
            }
            if (role == "admin") {
                prefs.edit().putString("name", prefs.getString("name", "Evangelos") ?: "Evangelos").apply()
            }
        }
    }
}

private val athens = ZoneId.of("Europe/Athens")
private fun todayStr(): String = LocalDate.now(athens).format(DateTimeFormatter.ISO_LOCAL_DATE)

private enum class Tab { Star, Martingale, Info }

@Composable
private fun AppRoot(prefs: SharedPreferences) {
    var user by remember {
        val email = prefs.getString("email", "") ?: ""
        mutableStateOf(
            if (prefs.getBoolean("logged_in", false)) {
                User(
                    id = prefs.getInt("uid", 1),
                    email = DemoAuth.normalizeEmail(email),
                    name = prefs.getString("name", "Χρήστης") ?: "Χρήστης",
                    // Always derive admin from email — never trust stale cached role alone
                    role = DemoAuth.roleForEmail(email),
                )
            } else null
        )
    }
    if (user == null) {
        AuthScreen(onLoggedIn = { u ->
            prefs.edit()
                .putBoolean("logged_in", true)
                .putInt("uid", u.id)
                .putString("email", u.email)
                .putString("name", u.name)
                .putString("role", u.role)
                .apply()
            user = u
        })
    } else {
        MainTabs(user = user!!, prefs = prefs, onLogout = {
            prefs.edit().clear().apply()
            user = null
        })
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BiColors.Gold,
    unfocusedBorderColor = BiColors.Stroke,
    focusedLabelColor = BiColors.Gold,
    cursorColor = BiColors.Gold,
    focusedTextColor = BiColors.Text,
    unfocusedTextColor = BiColors.Text,
    unfocusedLabelColor = BiColors.TextMuted,
)

@Composable
private fun AuthScreen(onLoggedIn: (User) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var modeRegister by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(BiColors.Bg, BiColors.BgElevated, BiColors.Navy))
            )
    ) {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(72.dp)
                    .background(BiColors.Gold.copy(alpha = 0.15f), CircleShape)
                    .border(1.dp, BiColors.Gold.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.EmojiEvents, null, tint = BiColors.Gold, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text("BettingInvestor", style = MaterialTheme.typography.headlineMedium, color = BiColors.Gold, fontWeight = FontWeight.Bold)
            Text("Premium προγνωστικά", color = BiColors.TextMuted)
            Spacer(Modifier.height(28.dp))
            if (modeRegister) {
                OutlinedTextField(name, { name = it }, label = { Text("Όνομα") }, modifier = Modifier.fillMaxWidth(), colors = fieldColors(), singleLine = true, shape = RoundedCornerShape(14.dp))
                Spacer(Modifier.height(10.dp))
            }
            OutlinedTextField(
                email, { email = it },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Email,
                ),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                password, { password = it },
                label = { Text("Κωδικός") },
                modifier = Modifier.fillMaxWidth(),
                colors = fieldColors(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                visualTransformation = PasswordVisualTransformation(),
            )
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error!!, color = BiColors.Loss, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    error = null
                    val e = DemoAuth.normalizeEmail(email)
                    val p = password.trim()
                    if (e.isBlank() || p.length < 4) {
                        error = "Συμπλήρωσε email και κωδικό"
                        return@Button
                    }
                    busy = true
                    scope.launch {
                        try {
                            if (modeRegister) {
                                if (name.isBlank()) { error = "Βάλε όνομα"; busy = false; return@launch }
                                val (_, u) = ApiClient.register(name.trim(), e, p)
                                onLoggedIn(u)
                            } else {
                                val (_, u) = ApiClient.login(e, p)
                                onLoggedIn(u)
                            }
                        } catch (_: Exception) {
                            // Offline / demo fallback
                            if (DemoAuth.isAdminEmail(e)) {
                                onLoggedIn(User(1, e, "Evangelos", "admin"))
                            } else if (modeRegister) {
                                if (name.isBlank()) { error = "Βάλε όνομα"; busy = false; return@launch }
                                onLoggedIn(User(2, e, name.trim(), "user"))
                            } else {
                                onLoggedIn(User(2, e, e.substringBefore("@").ifBlank { "Χρήστης" }, "user"))
                            }
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BiColors.Gold, contentColor = BiColors.Bg),
            ) { Text(if (modeRegister) "Εγγραφή" else "Σύνδεση", fontWeight = FontWeight.Bold) }
            TextButton(onClick = { modeRegister = !modeRegister; error = null }) {
                Text(if (modeRegister) "Έχεις λογαριασμό; Σύνδεση" else "Νέος χρήστης; Εγγραφή", color = BiColors.Gold)
            }
            Text(
                "Σημείο Ημέρας δωρεάν · Martingale 0,50€/ημέρα",
                style = MaterialTheme.typography.bodySmall,
                color = BiColors.TextMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MainTabs(user: User, prefs: SharedPreferences, onLogout: () -> Unit) {
    var tab by remember { mutableStateOf(Tab.Star) }
    val mgUnlocked = user.isAdmin || LocalDemoStore.isPaidUnlocked(false) || prefs.getBoolean("paid_unlocked", false)
    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = BiColors.BgElevated,
                tonalElevation = 0.dp,
            ) {
                NavigationBarItem(
                    selected = tab == Tab.Star,
                    onClick = { tab = Tab.Star },
                    icon = { Icon(Icons.Default.EmojiEvents, null) },
                    label = { Text("Σημείο") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = BiColors.Gold,
                        selectedTextColor = BiColors.Gold,
                        indicatorColor = BiColors.Gold.copy(alpha = 0.15f),
                        unselectedIconColor = BiColors.TextMuted,
                        unselectedTextColor = BiColors.TextMuted,
                    ),
                )
                NavigationBarItem(
                    selected = tab == Tab.Martingale,
                    onClick = { tab = Tab.Martingale },
                    icon = { Icon(if (mgUnlocked) Icons.Default.TrendingUp else Icons.Default.Lock, null) },
                    label = { Text("Martingale") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = BiColors.Gold,
                        selectedTextColor = BiColors.Gold,
                        indicatorColor = BiColors.Gold.copy(alpha = 0.15f),
                        unselectedIconColor = BiColors.TextMuted,
                        unselectedTextColor = BiColors.TextMuted,
                    ),
                )
                NavigationBarItem(
                    selected = tab == Tab.Info,
                    onClick = { tab = Tab.Info },
                    icon = { Icon(Icons.Default.Info, null) },
                    label = { Text("Πληροφορίες") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = BiColors.Gold,
                        selectedTextColor = BiColors.Gold,
                        indicatorColor = BiColors.Gold.copy(alpha = 0.15f),
                        unselectedIconColor = BiColors.TextMuted,
                        unselectedTextColor = BiColors.TextMuted,
                    ),
                )
            }
        },
        containerColor = BiColors.Bg,
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.Star -> StarPage(user, onLogout)
                Tab.Martingale -> MartingalePage(user, prefs, onLogout)
                Tab.Info -> InfoPage(onLogout)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StarPage(user: User, onLogout: () -> Unit) {
    val today = todayStr()
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Tip?>(null) }
    var tick by remember { mutableStateOf(0) }
    var liveMap by remember { mutableStateOf<Map<Int, LiveScore>>(emptyMap()) }
    var remoteStars by remember { mutableStateOf<List<Tip>?>(null) }
    LaunchedEffect(Unit) {
        while (isActive) {
            try {
                liveMap = ApiClient.liveScores(today)
                remoteStars = ApiClient.tips(kind = "star").tips
            } catch (_: Exception) { }
            delay(45_000)
        }
    }
    val allStars = remember(tick, remoteStars) { remoteStars ?: LocalDemoStore.starTips(null) }
    val sortedStars = TipSort.tipsNewestFirst(allStars).map { tip ->
        val live = tip.live ?: liveMap[tip.id]
        if (live != null) tip.copy(live = live) else tip
    }
    val todayStars = sortedStars.filter { it.tipDate == today }
    val history = sortedStars.filter { it.tipDate != today }
    val stats = remember(tick) { LocalDemoStore.starStats() }
    // cumulative map for history display (oldest→newest then look up)
    val cumById = remember(tick) {
        val ordered = allStars.filter { it.resultWon != null }.sortedWith(compareBy<Tip> { it.tipDate }.thenBy { it.id })
        var c = 0.0
        buildMap {
            for (t in ordered) {
                val p = LocalDemoStore.starProfit(t) ?: 0.0
                c += p
                put(t.id, c)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Σημείο Ημέρας", fontWeight = FontWeight.Bold, color = BiColors.Text)
                        Text(
                            (if (user.isAdmin) "Admin · " else "") + "Stake 1€ / σημείο",
                            style = MaterialTheme.typography.bodySmall,
                            color = BiColors.TextMuted,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { tick++ }) { Icon(Icons.Default.Refresh, null, tint = BiColors.Gold) }
                    IconButton(onClick = onLogout) { Icon(Icons.AutoMirrored.Filled.Logout, null, tint = BiColors.Gold) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BiColors.BgElevated),
            )
        },
        floatingActionButton = {
            if (user.isAdmin) {
                FloatingActionButton(
                    onClick = { editing = null; showEditor = true },
                    containerColor = BiColors.Gold,
                    contentColor = BiColors.Bg,
                ) { Icon(Icons.Default.Add, null) }
            }
        },
        containerColor = BiColors.Bg,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 100.dp, top = 12.dp),
        ) {
            item { SectionHeader("Σημείο Ημέρας", today) }
            if (todayStars.isEmpty()) {
                item { EmptyHint("Δεν έχει αναρτηθεί ακόμα το Σημείο Ημέρας.") }
            } else {
                items(todayStars, key = { "t-${it.id}" }) { tip ->
                    PremiumTipCard(
                        tip = tip,
                        flatProfit = LocalDemoStore.starProfit(tip),
                        cumulative = cumById[tip.id],
                        showFlatStake = true,
                        isAdmin = user.isAdmin,
                        onEdit = { editing = tip; showEditor = true },
                        onDelete = { LocalDemoStore.deleteTip(tip.id); tick++ },
                    )
                }
            }
            item { SectionHeader("Ιστορικό", "Όλα τα Σημεία Ημέρας · stake 1€") }
            items(history, key = { "h-${it.id}" }) { tip ->
                PremiumTipCard(
                    tip = tip,
                    flatProfit = LocalDemoStore.starProfit(tip),
                    cumulative = cumById[tip.id],
                    showFlatStake = true,
                    isAdmin = user.isAdmin,
                    onEdit = { editing = tip; showEditor = true },
                    onDelete = { LocalDemoStore.deleteTip(tip.id); tick++ },
                )
            }
            item { SectionHeader("Στατιστικά Σημείων", "Με βάση 1€ ανά σημείο") }
            item { StatsDashboard(stats) }
        }
    }

    if (showEditor) {
        TipEditorDialog(editing, today, true, { showEditor = false }) { match, market, odds, analysis, date, time, sport, pick, star, stake ->
            if (editing == null) LocalDemoStore.createTip(match, market, odds, analysis, date, time, sport, pick, star, stake)
            else LocalDemoStore.updateTip(editing!!.copy(matchName = match, market = market, odds = odds, analysis = analysis, tipDate = date, tipTime = time, sport = sport, pickType = pick, starOfDay = star, stake = stake))
            showEditor = false; tick++
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaidPage(user: User, prefs: SharedPreferences, onLogout: () -> Unit) {
    var tick by remember { mutableStateOf(0) }
    // Recompute every composition from live user.isAdmin — admin never paywalled
    val unlocked = user.isAdmin || LocalDemoStore.isPaidUnlocked(false)
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Tip?>(null) }
    val today = todayStr()
    var liveMap by remember { mutableStateOf<Map<Int, LiveScore>>(emptyMap()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            try { liveMap = ApiClient.liveScores(today) } catch (_: Exception) { }
            delay(45_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Ημερήσια προγνωστικά", fontWeight = FontWeight.Bold, color = BiColors.Text)
                        Text(
                            when {
                                user.isAdmin -> "Admin · πλήρης πρόσβαση"
                                unlocked -> "Ξεκλειδωμένα"
                                else -> "Απαιτείται πάσο 0,50€"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = BiColors.TextMuted,
                        )
                    }
                },
                actions = {
                    if (unlocked) IconButton(onClick = { tick++ }) { Icon(Icons.Default.Refresh, null, tint = BiColors.Gold) }
                    IconButton(onClick = onLogout) { Icon(Icons.AutoMirrored.Filled.Logout, null, tint = BiColors.Gold) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BiColors.BgElevated),
            )
        },
        floatingActionButton = {
            if (user.isAdmin && unlocked) {
                FloatingActionButton(onClick = { editing = null; showEditor = true }, containerColor = BiColors.Gold, contentColor = BiColors.Bg) {
                    Icon(Icons.Default.Add, null)
                }
            }
        },
        containerColor = BiColors.Bg,
    ) { padding ->
        if (!unlocked) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(88.dp).background(BiColors.Gold.copy(alpha = 0.12f), CircleShape)
                        .border(1.dp, BiColors.Gold.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Lock, null, tint = BiColors.Gold, modifier = Modifier.size(40.dp)) }
                Spacer(Modifier.height(20.dp))
                Text("Ημερήσιο πάσο", style = MaterialTheme.typography.headlineSmall, color = BiColors.Text, fontWeight = FontWeight.Bold)
                Text("0,50€", style = MaterialTheme.typography.displaySmall, color = BiColors.Gold, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Ξεκλείδωσε τα υπόλοιπα tips της ημέρας, το ιστορικό και τα στατιστικά.\nΤο Σημείο Ημέρας μένει δωρεάν στην καρτέλα Επιλογές.",
                    textAlign = TextAlign.Center,
                    color = BiColors.TextMuted,
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        LocalDemoStore.unlockPaid()
                        prefs.edit().putBoolean("paid_unlocked", true).apply()
                        tick++
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BiColors.Gold, contentColor = BiColors.Bg),
                ) { Text("Demo: Αγορά πάσου 0,50€", fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(8.dp))
                Text("Χωρίς χρέευση Google Play (stub).", color = BiColors.TextMuted, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            val paidList = remember(tick, unlocked) { LocalDemoStore.paidTips(user.isAdmin, null).second }
            val sortedPaid = TipSort.tipsNewestFirst(paidList).map { tip -> liveMap[tip.id]?.let { tip.copy(live = it) } ?: tip }
            val todayPaid = sortedPaid.filter { it.tipDate == today }
            val histPaid = sortedPaid.filter { it.tipDate != today }
            val stats = remember(tick, unlocked) { LocalDemoStore.paidStats(user.isAdmin) }
            val systems = remember(tick, unlocked) { LocalDemoStore.systemsForPaid(user.isAdmin) }

            LazyColumn(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 100.dp, top = 12.dp),
            ) {
                item { SectionHeader("Σήμερα", today) }
                if (todayPaid.isEmpty()) item { EmptyHint("Δεν υπάρχουν άλλα tips σήμερα.") }
                else items(todayPaid, key = { "pt-${it.id}" }) { tip ->
                    PremiumTipCard(tip, tip.profit, null, false, user.isAdmin,
                        { editing = tip; showEditor = true },
                        { LocalDemoStore.deleteTip(tip.id); tick++ })
                }
                item { SectionHeader("Ιστορικό", "Χωρίς Σημεία Ημέρας") }
                items(histPaid, key = { "ph-${it.id}" }) { tip ->
                    PremiumTipCard(tip, tip.profit, null, false, user.isAdmin,
                        { editing = tip; showEditor = true },
                        { LocalDemoStore.deleteTip(tip.id); tick++ })
                }
                if (stats != null) {
                    item { SectionHeader("Στατιστικά", "Ημερήσια tips") }
                    item { StatsDashboard(stats) }
                }
                if (systems.isNotEmpty()) {
                    item { SectionHeader("Συστήματα", null) }
                    items(systems) { d ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = BiColors.Card),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text("${d.tipDate} · ${d.systemName}", fontWeight = FontWeight.SemiBold, color = BiColors.Text)
                                d.dayProfit?.let { Text("Κέρδος ημέρας: ${fmtMoney(it)}", color = moneyColor(it)) }
                                d.progressive?.let { Text("Προοδευτικό: ${fmtMoney(it)}", color = BiColors.TextMuted) }
                            }
                        }
                    }
                }
            }
            if (showEditor) {
                TipEditorDialog(editing, today, false, { showEditor = false }) { match, market, odds, analysis, date, time, sport, pick, star, stake ->
                    if (editing == null) LocalDemoStore.createTip(match, market, odds, analysis, date, time, sport, pick, star, stake)
                    else LocalDemoStore.updateTip(editing!!.copy(matchName = match, market = market, odds = odds, analysis = analysis, tipDate = date, tipTime = time, sport = sport, pickType = pick, starOfDay = star, stake = stake))
                    showEditor = false; tick++
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoPage(onLogout: () -> Unit) {
    val context = LocalContext.current
    var serverStatus by remember { mutableStateOf("Έλεγχος…") }
    LaunchedEffect(Unit) {
        serverStatus = try {
            val map = ApiClient.liveScores()
            val health = withContext(kotlinx.coroutines.Dispatchers.IO) {
                // lightweight: reuse live-scores date as reachability; health via tips star
                ApiClient.tips(kind = "star")
            }
            "Online · tips ${health.tips.size} · scores ${map.size} · today ${health.today}"
        } catch (e: Exception) {
            "Offline / σφάλμα: ${e.message?.take(80) ?: "άγνωστο"}"
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Πληροφορίες", fontWeight = FontWeight.Bold, color = BiColors.Text)
                        Text("BettingInvestor Tips", style = MaterialTheme.typography.bodySmall, color = BiColors.TextMuted)
                    }
                },
                actions = {
                    IconButton(onClick = onLogout) { Icon(Icons.AutoMirrored.Filled.Logout, null, tint = BiColors.Gold) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BiColors.BgElevated),
            )
        },
        containerColor = BiColors.Bg,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 100.dp, top = 12.dp),
        ) {
            item { SectionHeader("Τιμοκατάλογος", null) }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = BiColors.Card),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, BiColors.Stroke.copy(alpha = 0.6f), RoundedCornerShape(18.dp)),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Σημείο Ημέρας", fontWeight = FontWeight.Bold, color = BiColors.Gold)
                        Text("Δωρεάν — η επιλογή της ημέρας με ιστορικό και στατιστικά (stake 1€).", color = BiColors.TextMuted)
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(BiColors.Stroke.copy(alpha = 0.5f)))
                        Spacer(Modifier.height(4.dp))
                        Text("Martingale", fontWeight = FontWeight.Bold, color = BiColors.Gold)
                        Text("0,50€ / ημέρα — όλες οι επιλογές (ημερήσια + Σημείο Ημέρας), live σκορ, pool & progression.", color = BiColors.TextMuted)
                    }
                }
            }
            item { SectionHeader("Version", null) }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = BiColors.Card),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, BiColors.Stroke.copy(alpha = 0.6f), RoundedCornerShape(18.dp)),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", fontWeight = FontWeight.SemiBold, color = BiColors.Text)
                        Text("API: ${BuildConfig.API_BASE_URL}", color = BiColors.TextMuted, fontSize = 12.sp)
                        Text(serverStatus, color = BiColors.TextMuted, fontSize = 12.sp)
                    }
                }
            }
            item { SectionHeader("Support", null) }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = BiColors.Card),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().border(1.dp, BiColors.Stroke.copy(alpha = 0.6f), RoundedCornerShape(18.dp)),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Επικοινωνία", fontWeight = FontWeight.Bold, color = BiColors.Text)
                        Text("bettinginvestor@hotmail.com", color = BiColors.TextMuted)
                        Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_SENDTO).apply {
                                    data = Uri.parse("mailto:bettinginvestor@hotmail.com")
                                    putExtra(Intent.EXTRA_SUBJECT, "BettingInvestor Tips")
                                }
                                context.startActivity(Intent.createChooser(intent, "Email"))
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BiColors.Gold, contentColor = BiColors.Bg),
                        ) { Text("Αποστολή email", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String?) {
    Column(Modifier.padding(top = 4.dp, bottom = 2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = BiColors.Gold)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = BiColors.TextMuted)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, color = BiColors.TextMuted, modifier = Modifier.padding(vertical = 8.dp))
}

private fun sportIcon(sport: String?): ImageVector = when (sport) {
    "Τένις" -> Icons.Default.SportsTennis
    "Μπάσκετ" -> Icons.Default.SportsBasketball
    "Ποδόσφαιρο" -> Icons.Default.SportsSoccer
    else -> Icons.Default.Sports
}

private fun fmtMoney(v: Double): String = (if (v >= 0) "+" else "") + "%.2f".format(v) + " €"
private fun moneyColor(v: Double): Color = if (v >= 0) BiColors.Win else BiColors.Loss

@Composable
private fun PremiumTipCard(
    tip: Tip,
    flatProfit: Double?,
    cumulative: Double?,
    showFlatStake: Boolean,
    isAdmin: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BiColors.Card),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, BiColors.Stroke.copy(alpha = 0.6f), RoundedCornerShape(18.dp)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).background(BiColors.Gold.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(sportIcon(tip.sport), null, tint = BiColors.Gold, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        tip.matchName,
                        fontWeight = FontWeight.Bold,
                        color = BiColors.Text,
                        fontSize = 16.sp,
                    )
                    Text(
                        listOfNotNull(tip.tipDate, tip.tipTime, tip.sport).joinToString(" · "),
                        color = BiColors.TextMuted,
                        fontSize = 12.sp,
                    )
                    if (tip.starOfDay) {
                        Spacer(Modifier.height(6.dp))
                        StarOfDayBadge(compact = true)
                    }
                    tip.live?.takeIf { it.live || (tip.resultWon == null && (it.score != null || it.status.isNotBlank())) }?.let { live ->
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (live.live) {
                                Box(
                                    Modifier
                                        .background(BiColors.Loss, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                ) {
                                    Text("LIVE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                listOfNotNull(live.score, live.status.takeIf { it.isNotBlank() }).joinToString(" · "),
                                color = if (live.live) BiColors.Loss else BiColors.TextMuted,
                                fontWeight = if (live.live) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
                if (isAdmin) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, null, tint = BiColors.Gold, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, null, tint = BiColors.Loss, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = {},
                    label = { Text(tip.market, fontWeight = FontWeight.SemiBold) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = BiColors.CardAlt,
                        labelColor = BiColors.Text,
                    ),
                    border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = BiColors.Stroke),
                )
                tip.odds?.let { odds ->
                    Box(
                        Modifier
                            .background(BiColors.Gold, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text("%.2f".format(odds), fontWeight = FontWeight.Bold, color = BiColors.Bg, fontSize = 14.sp)
                    }
                }
                tip.resultWon?.let { won ->
                    Box(
                        Modifier
                            .background(if (won) BiColors.Win.copy(alpha = 0.18f) else BiColors.Loss.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(if (won) "✅ Νίκη" else "❌ Ήττα", fontWeight = FontWeight.SemiBold, color = if (won) BiColors.Win else BiColors.Loss, fontSize = 13.sp)
                    }
                }
            }
            if (showFlatStake) {
                Spacer(Modifier.height(10.dp))
                Text("Ποντάρισμα: 1,00 € (σταθερό)", color = BiColors.TextMuted, fontSize = 13.sp)
                flatProfit?.let {
                    Text("Κέρδος σημείου: ${fmtMoney(it)}", color = moneyColor(it), fontWeight = FontWeight.SemiBold)
                }
                cumulative?.let {
                    Text("Σωρευτικό: ${fmtMoney(it)}", color = BiColors.TextMuted, fontSize = 13.sp)
                }
            } else {
                tip.stake?.let { Spacer(Modifier.height(8.dp)); Text("Ποντάρισμα: ${"%.2f".format(it)} €", color = BiColors.TextMuted, fontSize = 13.sp) }
                flatProfit?.let { Text("Κέρδος/Ζημιά: ${fmtMoney(it)}", color = moneyColor(it), fontWeight = FontWeight.SemiBold) }
            }
            tip.resultDetail?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = BiColors.TextMuted, fontSize = 12.sp)
            }
            tip.analysis?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = BiColors.Text.copy(alpha = 0.85f), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun StatsDashboard(stats: StatsResponse) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HeadlineStat("Win rate", "${stats.winRate}%", BiColors.Gold, Modifier.weight(1f))
            HeadlineStat("Tips", "${stats.totalPicks}", BiColors.Green, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HeadlineStat("Κέρδος", fmtMoney(stats.tipsProfit), moneyColor(stats.tipsProfit), Modifier.weight(1f))
            HeadlineStat("ROI", "${"%.1f".format(stats.roiPct)}%", moneyColor(stats.roiPct), Modifier.weight(1f))
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = BiColors.Card),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Προοδευτικό κέρδος", fontWeight = FontWeight.Bold, color = BiColors.Text)
                Spacer(Modifier.height(12.dp))
                if (stats.progressive.isEmpty()) {
                    Text("Χωρίς δεδομένα ακόμα", color = BiColors.TextMuted)
                } else {
                    ProfitChart(stats.progressive, Modifier.fillMaxWidth().height(140.dp))
                }
            }
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = BiColors.Card),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Φόρμα (τελευταία)", fontWeight = FontWeight.Bold, color = BiColors.Text)
                Spacer(Modifier.height(10.dp))
                val last = stats.form.takeLast(12)
                if (last.isEmpty()) Text("—", color = BiColors.TextMuted)
                else LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(last.size) { i ->
                        val win = last[i]
                        Box(
                            Modifier.size(22.dp).background(if (win) BiColors.Win else BiColors.Loss, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (win) "✓" else "✗", color = BiColors.Bg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        if (stats.bySport.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = BiColors.Card),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Ανά άθλημα", fontWeight = FontWeight.Bold, color = BiColors.Text)
                    stats.bySport.forEach { row ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(sportIcon(row.name), null, tint = BiColors.Gold, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(row.name, fontWeight = FontWeight.SemiBold, color = BiColors.Text)
                                Text("${row.wins}W–${row.losses}L · ${row.winPct}%", color = BiColors.TextMuted, fontSize = 12.sp)
                            }
                            Text(fmtMoney(row.profit), color = moneyColor(row.profit), fontWeight = FontWeight.SemiBold)
                        }
                        LinearProgressIndicator(
                            progress = { (row.winPct / 100.0).toFloat().coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = BiColors.Gold,
                            trackColor = BiColors.Stroke,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeadlineStat(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BiColors.Card),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.border(1.dp, BiColors.Stroke.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(label, color = BiColors.TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, color = accent, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        }
    }
}

@Composable
private fun ProfitChart(points: List<Double>, modifier: Modifier = Modifier) {
    val gold = BiColors.Gold
    val win = BiColors.Win
    val stroke = BiColors.Stroke
    val muted = BiColors.TextMuted
    Canvas(modifier) {
        if (points.isEmpty()) return@Canvas
        val minV = minOf(0.0, points.min())
        val maxV = maxOf(0.0, points.max())
        val range = (maxV - minV).let { if (it < 0.01) 1.0 else it }
        val pad = 8.dp.toPx()
        val w = size.width - pad * 2
        val h = size.height - pad * 2
        // zero line
        val zeroY = pad + h * (1f - ((0.0 - minV) / range).toFloat())
        drawLine(stroke, Offset(pad, zeroY), Offset(pad + w, zeroY), strokeWidth = 2f)
        val path = Path()
        points.forEachIndexed { i, v ->
            val x = pad + w * (i / (points.size - 1).coerceAtLeast(1).toFloat())
            val y = pad + h * (1f - ((v - minV) / range).toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, gold, style = Stroke(width = 4f, cap = StrokeCap.Round))
        points.forEachIndexed { i, v ->
            val x = pad + w * (i / (points.size - 1).coerceAtLeast(1).toFloat())
            val y = pad + h * (1f - ((v - minV) / range).toFloat())
            drawCircle(if (v >= 0) win else BiColors.Loss, radius = 5f, center = Offset(x, y))
        }
    }
}

@Composable
private fun TipEditorDialog(
    initial: Tip?,
    defaultDate: String,
    defaultStar: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, Double, String, String, String?, String, String?, Boolean, Double?) -> Unit,
) {
    var match by remember { mutableStateOf(initial?.matchName ?: "") }
    var market by remember { mutableStateOf(initial?.market ?: "") }
    var odds by remember { mutableStateOf(initial?.odds?.toString() ?: "") }
    var analysis by remember { mutableStateOf(initial?.analysis ?: "") }
    var date by remember { mutableStateOf(initial?.tipDate ?: defaultDate) }
    var time by remember { mutableStateOf(initial?.tipTime ?: "") }
    var sport by remember { mutableStateOf(initial?.sport ?: "Ποδόσφαιρο") }
    var pick by remember { mutableStateOf(initial?.pickType ?: "") }
    var star by remember { mutableStateOf(initial?.starOfDay ?: defaultStar) }
    var stake by remember { mutableStateOf(initial?.stake?.toString() ?: if (defaultStar) "1.00" else "0.30") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BiColors.Card,
        title = { Text(if (initial == null) "Νέο προγνωστικό" else "Επεξεργασία", color = BiColors.Text) },
        text = {
            Column(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(match, { match = it }, label = { Text("Αγώνας") }, singleLine = true, colors = fieldColors())
                OutlinedTextField(market, { market = it }, label = { Text("Αγορά") }, singleLine = true, colors = fieldColors())
                OutlinedTextField(odds, { odds = it }, label = { Text("Απόδοση") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = fieldColors())
                if (!star) OutlinedTextField(stake, { stake = it }, label = { Text("Ποντάρισμα €") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = fieldColors())
                else Text("Σημείο Ημέρας: σταθερό 1€", color = BiColors.Gold, fontSize = 13.sp)
                OutlinedTextField(date, { date = it }, label = { Text("Ημερομηνία YYYY-MM-DD") }, singleLine = true, colors = fieldColors())
                OutlinedTextField(time, { time = it }, label = { Text("Ώρα") }, singleLine = true, colors = fieldColors())
                OutlinedTextField(sport, { sport = it }, label = { Text("Άθλημα") }, singleLine = true, colors = fieldColors())
                OutlinedTextField(pick, { pick = it }, label = { Text("Τύπος") }, singleLine = true, colors = fieldColors())
                OutlinedTextField(analysis, { analysis = it }, label = { Text("Ανάλυση") }, minLines = 2, colors = fieldColors())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = star, onCheckedChange = { star = it }, colors = CheckboxDefaults.colors(checkedColor = BiColors.Gold))
                    Text("Σημείο Ημέρας (badge)", color = BiColors.Text)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val o = odds.replace(',', '.').toDoubleOrNull()
                val st = if (star) 1.0 else stake.replace(',', '.').toDoubleOrNull()
                if (match.isNotBlank() && market.isNotBlank() && o != null) {
                    onSave(match.trim(), market.trim(), o, analysis.trim(), date.trim(), time.trim().ifBlank { null }, sport.trim(), pick.trim().ifBlank { null }, star, st)
                }
            }) { Text("Αποθήκευση", color = BiColors.Gold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Άκυρο", color = BiColors.TextMuted) } },
    )
}
