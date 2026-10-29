package gr.bettinginvestor.tips.ui

import android.content.SharedPreferences
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gr.bettinginvestor.tips.data.ApiClient
import gr.bettinginvestor.tips.data.LocalDemoStore
import gr.bettinginvestor.tips.data.MartingaleBet
import gr.bettinginvestor.tips.data.MartingaleState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import gr.bettinginvestor.tips.data.MartingaleStore
import gr.bettinginvestor.tips.data.User
import java.util.Locale

private fun eur(v: Double, decimals: Int = 2): String =
    String.format(Locale.US, "%.${decimals}f", v)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MartingalePage(user: User, prefs: SharedPreferences, onLogout: () -> Unit) {
    var tick by remember { mutableStateOf(0) }
    val unlocked = user.isAdmin || prefs.getBoolean("paid_unlocked", false) ||
        LocalDemoStore.isPaidUnlocked(user.isAdmin)
    var showAdd by remember { mutableStateOf(false) }
    var baseInput by remember { mutableStateOf(MartingaleStore.baseStake().toString()) }
    var starMultInput by remember { mutableStateOf(MartingaleStore.starMultiplier().toString()) }
    var lossMultInput by remember { mutableStateOf(MartingaleStore.lossMultiplier().toString()) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var remote by remember { mutableStateOf<MartingaleState?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(unlocked) {
        if (!unlocked) return@LaunchedEffect
        while (isActive) {
            MartingaleStore.syncFromTips(LocalDemoStore.allTips())
            try {
                if (ApiClient.token != null) remote = ApiClient.martingale()
            } catch (_: Exception) { }
            delay(45_000)
        }
    }

    // Force local recompute when tick changes; prefer remote when available
    val localState = remember(tick) {
        MartingaleStore.syncFromTips(LocalDemoStore.allTips())
        MartingaleStore.state()
    }
    val state = remote ?: localState
    val history = remember(tick, remote) {
        val s = remote
        if (s != null) {
            listOf(s.startingPool) + s.bets.filter { it.poolAfter != null }.sortedBy { it.id }.mapNotNull { it.poolAfter }
        } else {
            MartingaleStore.poolHistory()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Martingale", fontWeight = FontWeight.Bold, color = BiColors.Text)
                        val sub = when {
                            user.isAdmin -> "Admin · pool " + eur(MartingaleStore.STARTING_POOL, 0) + "€"
                            unlocked -> "Ξεκλειδωμένο"
                            else -> "Απαιτείται πάσο 0,50€"
                        }
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = BiColors.TextMuted)
                    }
                },
                actions = {
                    if (unlocked) {
                        IconButton(onClick = {
                            tick++
                            scope.launch {
                                try { if (ApiClient.token != null) remote = ApiClient.martingale() } catch (_: Exception) { }
                            }
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = BiColors.Gold)
                        }
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = BiColors.Gold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BiColors.BgElevated),
            )
        },
        floatingActionButton = {
            if (user.isAdmin && unlocked) {
                FloatingActionButton(
                    onClick = { showAdd = true },
                    containerColor = BiColors.Gold,
                    contentColor = BiColors.Bg,
                ) { Icon(Icons.Default.Add, contentDescription = null) }
            }
        },
        containerColor = BiColors.Bg,
    ) { padding ->
        if (!unlocked) {
            Paywall(padding) {
                LocalDemoStore.unlockPaid()
                prefs.edit().putBoolean("paid_unlocked", true).apply()
                tick++
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 100.dp, top = 12.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard("Pool", eur(state.pool) + " €", BiColors.Gold, Modifier.weight(1f))
                        MetricCard("Βήμα", "#" + state.cycleStep, BiColors.Green, Modifier.weight(1f))
                    }
                }
                item {
                    val todayStake = state.bets.firstOrNull { it.resultWon == null }?.stake ?: state.levelStake
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard("Στοίχημα", eur(todayStake) + " €", BiColors.Text, Modifier.weight(1f))
                        MetricCard("Level", eur(state.levelStake) + " €", BiColors.Loss, Modifier.weight(1f))
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard("Max DD", eur(state.maxDrawdown) + " €", BiColors.Loss, Modifier.weight(1f))
                        MetricCard("Base", eur(state.baseStake) + " €", BiColors.GoldDim, Modifier.weight(1f))
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard("Σημείο ×", "×" + eur(state.starMultiplier, 0), BiColors.Gold, Modifier.weight(1f))
                        MetricCard("Ήττα ×", "×" + eur(state.lossMultiplier, 0), BiColors.Loss, Modifier.weight(1f))
                    }
                }
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = BiColors.Card),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            state.ruleSummary,
                            modifier = Modifier.padding(14.dp),
                            color = BiColors.TextMuted,
                            fontSize = 12.sp,
                        )
                    }
                }

                val pending = state.bets.firstOrNull { it.resultWon == null }
                if (MartingaleStore.nextStakeExceedsPool(pending?.starOfDay == true) || (pending != null && pending.poolExceeded)) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = BiColors.Loss.copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = BiColors.Loss)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "Προσοχή: το επόμενο/τρέχον stake υπερβαίνει το διαθέσιμο pool.",
                                    color = BiColors.Loss,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = BiColors.Card),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Pool chart", fontWeight = FontWeight.Bold, color = BiColors.Text)
                            Spacer(Modifier.height(12.dp))
                            if (history.size < 2) {
                                Text(
                                    "Ξεκίνημα από " + eur(MartingaleStore.STARTING_POOL, 0) + "€ — χωρίς κινήσεις ακόμα.",
                                    color = BiColors.TextMuted,
                                )
                            } else {
                                PoolChart(history, Modifier.fillMaxWidth().height(140.dp))
                            }
                        }
                    }
                }

                if (user.isAdmin) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = BiColors.Card),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Admin ρυθμίσεις", fontWeight = FontWeight.Bold, color = BiColors.Gold)
                                OutlinedTextField(
                                    value = baseInput,
                                    onValueChange = { baseInput = it },
                                    label = { Text("Base stake €") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    colors = fieldColorsMg(),
                                )
                                OutlinedTextField(
                                    value = starMultInput,
                                    onValueChange = { starMultInput = it },
                                    label = { Text("Σημείο Ημέρας ×") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    colors = fieldColorsMg(),
                                )
                                OutlinedTextField(
                                    value = lossMultInput,
                                    onValueChange = { lossMultInput = it },
                                    label = { Text("Ήττα × (επόμενο level)") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    colors = fieldColorsMg(),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            baseInput.replace(',', '.').toDoubleOrNull()?.let { MartingaleStore.setBaseStake(it) }
                                            starMultInput.replace(',', '.').toDoubleOrNull()?.let { MartingaleStore.setStarMultiplier(it) }
                                            lossMultInput.replace(',', '.').toDoubleOrNull()?.let { MartingaleStore.setLossMultiplier(it) }
                                            tick++
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = BiColors.Gold,
                                            contentColor = BiColors.Bg,
                                        ),
                                    ) { Text("Αποθήκευση") }
                                    OutlinedButton(onClick = { showResetConfirm = true }) { Text("Reset 1000€") }
                                }
                            }
                        }
                    }
                }

                item {
                    Text("Ιστορικό στοιχημάτων", fontWeight = FontWeight.Bold, color = BiColors.Gold, fontSize = 18.sp)
                }

                if (state.bets.isEmpty()) {
                    item {
                        Text(
                            "Κενό ιστορικό. Pool " + eur(state.startingPool, 0) + "€ · base " + eur(state.baseStake) + "€.",
                            color = BiColors.TextMuted,
                        )
                    }
                } else {
                    items(state.bets, key = { it.id }) { bet ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = BiColors.Card),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, BiColors.Stroke.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(bet.matchName, fontWeight = FontWeight.Bold, color = BiColors.Text)
                                Text(
                                    bet.tipDate + " · " + bet.market + " · βήμα #" + bet.cycleStep +
                                        (if (bet.starOfDay) " · Σημείο" else ""),
                                    color = BiColors.TextMuted,
                                    fontSize = 12.sp,
                                )
                                bet.live?.takeIf { it.live || it.score != null || it.status.isNotBlank() }?.let { live ->
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
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(
                                        Modifier
                                            .background(BiColors.Gold, RoundedCornerShape(8.dp))
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            eur(bet.odds),
                                            color = BiColors.Bg,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                        )
                                    }
                                    Text("Stake " + eur(bet.stake) + " €", color = BiColors.Text)
                                }
                                Text(
                                    "Level " + eur(bet.levelBefore) + "€ · ×" + eur(bet.starMultiplierApplied, 0) +
                                        " σημείο · ήττα ×" + eur(bet.lossMultiplier, 0) +
                                        (if (bet.poolExceeded) " · POOL CAP" else ""),
                                    color = if (bet.poolExceeded) BiColors.Loss else BiColors.TextMuted,
                                    fontSize = 11.sp,
                                )
                                val won = bet.resultWon
                                if (won == null) {
                                    if (user.isAdmin) {
                                        Spacer(Modifier.height(8.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                onClick = {
                                                    MartingaleStore.settle(bet.id, true)
                                                    tick++
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = BiColors.Win,
                                                    contentColor = BiColors.Bg,
                                                ),
                                            ) { Text("Νίκη") }
                                            Button(
                                                onClick = {
                                                    MartingaleStore.settle(bet.id, false)
                                                    tick++
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = BiColors.Loss,
                                                    contentColor = BiColors.Text,
                                                ),
                                            ) { Text("Ήττα") }
                                            TextButton(onClick = {
                                                MartingaleStore.deletePending(bet.id)
                                                tick++
                                            }) { Text("Διαγραφή", color = BiColors.TextMuted) }
                                        }
                                    } else {
                                        Text("Εκκρεμεί αποτέλεσμα", color = BiColors.Gold)
                                    }
                                } else {
                                    val pl = eur(bet.profit ?: 0.0)
                                    val pool = eur(bet.poolAfter ?: 0.0)
                                    val label = if (won) "Νίκη P/L $pl € · pool $pool €" else "Ήττα P/L $pl € · pool $pool €"
                                    Text(
                                        label,
                                        color = if (won) BiColors.Win else BiColors.Loss,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddPickDialog(
            onDismiss = { showAdd = false },
            onSave = { match, market, odds, star ->
                MartingaleStore.addPick(match, market, odds, starOfDay = star)
                showAdd = false
                tick++
            },
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            containerColor = BiColors.Card,
            title = { Text("Reset Martingale;", color = BiColors.Text) },
            text = { Text("Επαναφορά σε pool 1000€ και κενό ιστορικό.", color = BiColors.TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    val b = baseInput.replace(',', '.').toDoubleOrNull() ?: MartingaleStore.DEFAULT_BASE
                    val sm = starMultInput.replace(',', '.').toDoubleOrNull() ?: MartingaleStore.DEFAULT_STAR_MULT
                    val lm = lossMultInput.replace(',', '.').toDoubleOrNull() ?: MartingaleStore.DEFAULT_LOSS_MULT
                    MartingaleStore.reset(b, sm, lm)
                    showResetConfirm = false
                    tick++
                }) { Text("Reset", color = BiColors.Loss) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Άκυρο", color = BiColors.TextMuted)
                }
            },
        )
    }
}

@Composable
private fun Paywall(padding: PaddingValues, onUnlock: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(88.dp)
                .background(BiColors.Gold.copy(alpha = 0.12f), CircleShape)
                .border(1.dp, BiColors.Gold.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = BiColors.Gold, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Martingale", style = MaterialTheme.typography.headlineSmall, color = BiColors.Text, fontWeight = FontWeight.Bold)
        Text("0,50€ / ημέρα", color = BiColors.Gold, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            "Ξεκλείδωσε όλες τις επιλογές (ημερήσια + Σημείο Ημέρας), live σκορ, pool 1000€, stakes & ιστορικό.\nΤο Σημείο Ημέρας μένει δωρεάν στην ομώνυμη καρτέλα.",
            textAlign = TextAlign.Center,
            color = BiColors.TextMuted,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onUnlock,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BiColors.Gold, contentColor = BiColors.Bg),
        ) { Text("Demo: Αγορά πάσου 0,50€", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun AddPickDialog(onDismiss: () -> Unit, onSave: (String, String, Double, Boolean) -> Unit) {
    var match by remember { mutableStateOf("") }
    var market by remember { mutableStateOf("") }
    var odds by remember { mutableStateOf("") }
    var star by remember { mutableStateOf(false) }
    val o = odds.replace(',', '.').toDoubleOrNull()
    val preview = MartingaleStore.stakeForOdds(o ?: 2.0, star)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BiColors.Card,
        title = { Text("Νέο Martingale pick", color = BiColors.Text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(match, { match = it }, label = { Text("Αγώνας") }, singleLine = true, colors = fieldColorsMg())
                OutlinedTextField(market, { market = it }, label = { Text("Προγνωστικό") }, singleLine = true, colors = fieldColorsMg())
                OutlinedTextField(
                    odds,
                    { odds = it },
                    label = { Text("Απόδοση") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = fieldColorsMg(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = star,
                        onCheckedChange = { star = it },
                        colors = CheckboxDefaults.colors(checkedColor = BiColors.Gold),
                    )
                    Text("Σημείο Ημέρας (×" + eur(MartingaleStore.starMultiplier(), 0) + ")", color = BiColors.Text)
                }
                if (o != null) {
                    Text("Υπολογισμένο stake: " + eur(preview) + " €", color = BiColors.Gold, fontWeight = FontWeight.Bold)
                    if (MartingaleStore.nextStakeExceedsPool(star)) {
                        Text("Υπερβαίνει το pool — θα πονταριστεί όλο το υπόλοιπο", color = BiColors.Loss)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (match.isNotBlank() && market.isNotBlank() && o != null && o > 1.0) {
                    onSave(match.trim(), market.trim(), o, star)
                }
            }) { Text("Καταχώριση", color = BiColors.Gold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Άκυρο", color = BiColors.TextMuted) } },
    )
}

@Composable
private fun fieldColorsMg() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BiColors.Gold,
    unfocusedBorderColor = BiColors.Stroke,
    focusedTextColor = BiColors.Text,
    unfocusedTextColor = BiColors.Text,
    cursorColor = BiColors.Gold,
    focusedLabelColor = BiColors.Gold,
)

@Composable
private fun MetricCard(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BiColors.Card),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.border(1.dp, BiColors.Stroke.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(label, color = BiColors.TextMuted, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, color = accent, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
    }
}

@Composable
private fun PoolChart(points: List<Double>, modifier: Modifier = Modifier) {
    val gold = BiColors.Gold
    val win = BiColors.Win
    val stroke = BiColors.Stroke
    Canvas(modifier) {
        if (points.isEmpty()) return@Canvas
        val minV = points.min()
        val maxV = points.max()
        val range = (maxV - minV).let { if (it < 0.01) 1.0 else it }
        val pad = 8.dp.toPx()
        val w = size.width - pad * 2
        val h = size.height - pad * 2
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
            drawCircle(win, radius = 5f, center = Offset(x, y))
        }
        val startY = pad + h * (1f - ((MartingaleStore.STARTING_POOL - minV) / range).toFloat())
        drawLine(stroke, Offset(pad, startY), Offset(pad + w, startY), strokeWidth = 2f)
    }
}
