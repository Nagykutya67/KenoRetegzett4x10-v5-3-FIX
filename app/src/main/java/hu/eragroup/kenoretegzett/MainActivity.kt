package hu.eragroup.kenoretegzett

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

private val Blue = Color(0xFF2563EB)
private val Background = Color(0xFFF1F5F9)
private val Ink = Color(0xFF0F172A)
private val Green = Color(0xFF34D399)
private val DarkGreen = Color(0xFF047857)
private val rowNames = listOf("A", "B", "C", "D")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Blue, background = Background)) {
                KenoApp()
            }
        }
    }
}

@Composable
fun KenoApp(viewModel: KenoViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val labels = listOf("FĹ‘oldal", "Tippek", "Ă‰rtĂ©kelĂ©s", "Statisztika", "BeĂˇllĂ­tĂˇsok")
    Scaffold(
        bottomBar = {
            NavigationBar {
                labels.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Text(listOf("âŚ‚", "â–Ł", "âś“", "â–Ą", "âš™")[index]) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().background(Background).padding(padding)
        ) {
            AppHeader(state) { viewModel.refresh() }
            when (selectedTab) {
                0 -> HomeScreen(state) { selectedTab = 4 }
                1 -> TipsScreen(state)
                2 -> EvaluationScreen(state)
                3 -> StatisticsScreen(state)
                else -> SettingsScreen(state) { settings -> viewModel.saveSettings(settings) }
            }
        }
    }
}

@Composable
private fun AppHeader(state: UiState, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Blue).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("KENĂ“ RĂ‰TEGZETT 4Ă—10", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("40 â†’ 2Ă—20 â†’ 4Ă—10", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Black)
            }
            Text(
                text = if (state.loading) "â€¦" else "â†»",
                color = Color.White,
                fontSize = 28.sp,
                modifier = Modifier.clickable { onRefresh() }
            )
        }
        Text("FrissĂ­tve: ${state.lastSync}", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
    }
}

@Composable
private fun HomeScreen(state: UiState, openSettings: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            MetricCard(
                "ElĹ‘zĹ‘ hĂşzĂˇs eredmĂ©nye",
                state.evaluation?.let { "${it.totalHits}/40 â€˘ P20 ${it.p20Hits}/20 â€˘ Q20 ${it.q20Hits}/20" }
                    ?: "MĂ©g nincs lezĂˇrt Ă©rtĂ©kelĂ©s"
            )
        }
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("KĂ¶vetkezĹ‘ tipp", fontWeight = FontWeight.Bold)
                    Text("CĂ©lhĂşzĂˇs: ${state.targetDate}")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SmallTwentyCard("P20 â€“ elsĹ‘dleges", state.snapshot?.p20.orEmpty(), Modifier.weight(1f))
                        SmallTwentyCard("Q20 â€“ kihĂ­vĂł", state.snapshot?.q20.orEmpty(), Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            Button(onClick = openSettings, modifier = Modifier.fillMaxWidth()) {
                Text("TalĂˇlatok elosztĂˇsa Ă©s paramĂ©terezĂ©s")
            }
        }
        state.error?.let { error -> item { MetricCard("FrissĂ­tĂ©si hiba", error) } }
    }
}

@Composable
private fun SmallTwentyCard(title: String, numbers: List<Int>, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFDBEAFE)).padding(12.dp)) {
        Column {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text("${numbers.size} szĂˇm", fontSize = 16.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun TipsScreen(state: UiState) {
    var subTab by remember { mutableIntStateOf(0) }
    Column {
        TabRow(selectedTabIndex = subTab) {
            listOf("Teljes kĂ©p", "P20 / Q20", "Sorok").forEachIndexed { index, label ->
                Tab(selected = subTab == index, onClick = { subTab = index }, text = { Text(label) })
            }
        }
        when (subTab) {
            0 -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { MetricCard("Teljes 40-es keret", "P20 + Q20, 40 kĂĽlĂ¶nbĂ¶zĹ‘ szĂˇm") }
                item { NumberCard("P20 â€“ elsĹ‘dleges", state.snapshot?.p20.orEmpty(), emptySet()) }
                item { NumberCard("Q20 â€“ kihĂ­vĂł", state.snapshot?.q20.orEmpty(), emptySet()) }
            }
            1 -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { NumberCard("P20 â€“ elsĹ‘dleges", state.snapshot?.p20.orEmpty(), emptySet()) }
                item { NumberCard("Q20 â€“ kihĂ­vĂł", state.snapshot?.q20.orEmpty(), emptySet()) }
            }
            else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(rowNames) { rowName ->
                    RowCard(
                        rowName,
                        state.snapshot?.rows?.get(rowName).orEmpty(),
                        state.snapshot?.retainedByRow?.get(rowName).orEmpty().toSet()
                    )
                }
            }
        }
    }
}

@Composable
private fun EvaluationScreen(state: UiState) {
    val evaluation = state.evaluation
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            MetricCard(
                "ElĹ‘zĹ‘ 4Ă—10 Ă©rtĂ©kelĂ©se",
                evaluation?.let { "${it.totalHits}/40 â€˘ P20 ${it.p20Hits}/20 â€˘ Q20 ${it.q20Hits}/20" }
                    ?: "MĂ©g nincs lezĂˇrt Ă©rtĂ©kelĂ©s"
            )
        }
        if (evaluation != null) {
            item { NumberCard("Hivatalos 20 szĂˇm", evaluation.actual, evaluation.actual.toSet()) }
            items(rowNames) { rowName ->
                RowCard(
                    "$rowName â€˘ ${evaluation.rowHits[rowName].orEmpty().size}/10",
                    evaluation.rows[rowName].orEmpty(),
                    evaluation.rowHits[rowName].orEmpty().toSet()
                )
            }
        }
    }
}

@Composable
private fun StatisticsScreen(state: UiState) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("GĂ¶rdĂĽlĹ‘ eredmĂ©nyek", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    StatisticsHeader()
                    StatisticsRow("30", state.stats30)
                    StatisticsRow("100", state.stats100)
                    StatisticsRow("500", state.stats500)
                }
            }
        }
    }
}

@Composable
private fun StatisticsHeader() {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Ablak", fontWeight = FontWeight.Bold)
        Text("40-es", fontWeight = FontWeight.Bold)
        Text("P20", fontWeight = FontWeight.Bold)
        Text("Q20", fontWeight = FontWeight.Bold)
        Text("jobb10", fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatisticsRow(label: String, statistics: RollStat) {
    fun average(value: Int): String {
        return if (statistics.n == 0) "â€“" else "%.2f".format(value.toDouble() / statistics.n.toDouble())
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(average(statistics.sum40))
        Text(average(statistics.sumP))
        Text(average(statistics.sumQ))
        Text(average(statistics.sumBest10))
    }
}

@Composable
private fun SettingsScreen(state: UiState, onSave: (AppSettings) -> Unit) {
    var settings by remember(state.settings) { mutableStateOf(state.settings) }
    var help by remember { mutableStateOf<Pair<String, String>?>(null) }
    var saved by remember { mutableStateOf(false) }
    val availableHits = state.snapshot?.previousHits.orEmpty().size

    fun changeDistribution(rowName: String, delta: Int) {
        val distribution = settings.hitDistribution.toMutableMap()
        val current = distribution[rowName] ?: 0
        val next = (current + delta).coerceIn(0, 10)
        val newTotal = distribution.values.sum() - current + next
        if (newTotal <= availableHits) {
            distribution[rowName] = next
            settings = settings.copy(hitDistribution = distribution)
        }
    }

    fun changeQuota(layerName: String, delta: Int) {
        val quotas = settings.quotas.toMutableMap()
        val current = quotas[layerName] ?: 0
        if (delta > 0) {
            val donor = quotas.filterKeys { it != layerName }.maxByOrNull { it.value }?.key ?: return
            if ((quotas[donor] ?: 0) <= 0) return
            quotas[layerName] = current + 1
            quotas[donor] = (quotas[donor] ?: 0) - 1
        } else if (delta < 0 && current > 0) {
            val receiver = quotas.filterKeys { it != layerName }.minByOrNull { it.value }?.key ?: return
            quotas[layerName] = current - 1
            quotas[receiver] = (quotas[receiver] ?: 0) + 1
        }
        if (quotas.values.sum() == 40) settings = settings.copy(quotas = quotas)
    }

    val helpTexts = parameterHelpTexts()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card {
                Column(Modifier.padding(14.dp)) {
                    ClickableHeading("TalĂˇlĂłk kĂ©zi szĂ©tosztĂˇsa") { help = "TalĂˇlĂłk kĂ©zi szĂ©tosztĂˇsa" to helpTexts.getValue("TalĂˇlĂłk kĂ©zi szĂ©tosztĂˇsa") }
                    Text("ValĂłdi talĂˇlatok: $availableHits", fontSize = 12.sp)
                    rowNames.forEach { rowName ->
                        StepperRow("$rowName sor", settings.hitDistribution[rowName] ?: 0, 0, 10, { delta -> changeDistribution(rowName, delta) })
                    }
                    MetricCard("Elosztott talĂˇlĂłk", "${settings.hitDistribution.values.sum()}/$availableHits")
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text("40-es keret rĂ©tegkvĂłtĂˇi", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    settings.quotas.forEach { entry ->
                        StepperRow(entry.key, entry.value, 0, 40, { delta -> changeQuota(entry.key, delta) }) {
                            help = entry.key to helpTexts.getValue(entry.key)
                        }
                    }
                    Text("Ă–sszesen: ${settings.quotas.values.sum()}/40", fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text("RangsorolĂˇsi paramĂ©terek", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    StepperRow("RĂ¶vid ablak", settings.shortWindow, 3, 10, { delta -> settings = settings.copy(shortWindow = (settings.shortWindow + delta).coerceIn(3, 10)) }) { help = "RĂ¶vid ablak" to helpTexts.getValue("RĂ¶vid ablak") }
                    StepperRow("AktivitĂˇsi ablak", settings.activityWindow, 5, 20, { delta -> settings = settings.copy(activityWindow = (settings.activityWindow + delta).coerceIn(5, 20)) }) { help = "AktivitĂˇsi ablak" to helpTexts.getValue("AktivitĂˇsi ablak") }
                    StepperRow("HĂˇttĂ©rablak", settings.backgroundWindow, 15, 50, { delta -> settings = settings.copy(backgroundWindow = (settings.backgroundWindow + delta).coerceIn(15, 50)) }) { help = "HĂˇttĂ©rablak" to helpTexts.getValue("HĂˇttĂ©rablak") }
                    StepperRow("StabilitĂˇsi ablak", settings.stabilityWindow, 20, 100, { delta -> settings = settings.copy(stabilityWindow = (settings.stabilityWindow + delta).coerceIn(20, 100)) }) { help = "StabilitĂˇsi ablak" to helpTexts.getValue("StabilitĂˇsi ablak") }
                    StepperRow("Napi maximĂˇlis csere", settings.maxDailyChange, 0, 40, { delta -> settings = settings.copy(maxDailyChange = (settings.maxDailyChange + delta).coerceIn(0, 40)) }) { help = "Napi maximĂˇlis csere" to helpTexts.getValue("Napi maximĂˇlis csere") }
                    StepperRow("IsmĂ©tlĹ‘dĂ©si sĂşly", settings.repeatWeight, -12, 12, { delta -> settings = settings.copy(repeatWeight = (settings.repeatWeight + delta).coerceIn(-12, 12)) }) { help = "IsmĂ©tlĹ‘dĂ©si sĂşly" to helpTexts.getValue("IsmĂ©tlĹ‘dĂ©si sĂşly") }
                }
            }
        }
        item {
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text("Automatika", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    ToggleRow("IndĂ­tĂˇskor frissĂ­tĂ©s", settings.refreshOnOpen) { settings = settings.copy(refreshOnOpen = it) }
                    ToggleRow("Csak Wi-Fi", settings.wifiOnly) { settings = settings.copy(wifiOnly = it) }
                }
            }
        }
        item {
            if (saved) Text("BeĂˇllĂ­tĂˇsok elmentve Ă©s alkalmazva.", color = DarkGreen, fontWeight = FontWeight.Bold)
            Button(
                onClick = { saved = true; onSave(settings) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("MentĂ©s Ă©s frissĂ­tĂ©s") }
        }
    }
    help?.let { selected ->
        AlertDialog(
            onDismissRequest = { help = null },
            title = { Text(selected.first) },
            text = { Text(selected.second) },
            confirmButton = { Button(onClick = { help = null }) { Text("BezĂˇrĂˇs") } }
        )
    }
}

private fun parameterHelpTexts(): Map<String, String> {
    return mapOf(
        "TalĂˇlĂłk kĂ©zi szĂ©tosztĂˇsa" to "Az elĹ‘zĹ‘, hitelesen Ă©rtĂ©kelt 40-es keret talĂˇlataibĂłl te adod meg, hĂˇny kerĂĽljĂ¶n garantĂˇltan az Aâ€“D sorokba. Nem kĂ¶telezĹ‘ mindet felhasznĂˇlni. Ha kevesebbet kĂ©rsz, a modell a legmagasabb relevanciapontĂş talĂˇlatokat vĂˇlasztja.",
        "VisszatĂ©rĹ‘" to "Az elĹ‘zĹ‘ hĂşzĂˇsban szereplĹ‘ szĂˇmok kvĂłtĂˇja. NĂ¶velĂ©se tĂ¶bb kĂ¶zvetlen ismĂ©tlĹ‘dĹ‘t, csĂ¶kkentĂ©se tĂ¶bb meglepetĂ©s- Ă©s kĂ¶zepes szĂˇmot enged be.",
        "RĂ¶vid meglepetĂ©s" to "A rĂ¶vid ablakban egyszer sem megjelent szĂˇmok kvĂłtĂˇja. NĂ¶velĂ©se nagyobb meglepetĂ©s-lefedĂ©st ad.",
        "Hideg" to "Az aktivitĂˇsi ablakban legfeljebb egyszer megjelent szĂˇmok kvĂłtĂˇja.",
        "KĂ¶zepes 2" to "Az aktivitĂˇsi ablakban pontosan kĂ©tszer megjelent szĂˇmok kvĂłtĂˇja.",
        "KĂ¶zepes 3" to "Az aktivitĂˇsi ablakban pontosan hĂˇromszor megjelent szĂˇmok kvĂłtĂˇja.",
        "RĂ¶vid ablak" to "A gyors aktivitĂˇsi Ă©s meglepetĂ©sjel hĂşzĂˇsszĂˇma. Kisebb Ă©rtĂ©k gyorsabb, de zajosabb; nagyobb Ă©rtĂ©k lassabb Ă©s stabilabb.",
        "AktivitĂˇsi ablak" to "A hideg Ă©s kĂ¶zepes rĂ©tegek besorolĂˇsi ablaka. AlapĂ©rtĂ©ke 10 hĂşzĂˇs.",
        "HĂˇttĂ©rablak" to "A rĂ©tegen belĂĽli kĂ¶zĂ©ptĂˇvĂş rangsorolĂˇs ablaka.",
        "StabilitĂˇsi ablak" to "A hosszabb aktivitĂˇsi hĂˇtteret mĂ©ri. NĂ¶velĂ©se lassĂ­tja a napi vĂˇltozĂˇst.",
        "Napi maximĂˇlis csere" to "Legfeljebb ennyi szĂˇm vĂˇltozhat az elĹ‘zĹ‘ 40-eshez kĂ©pest. Ez felsĹ‘ korlĂˇt, nem kĂ¶telezĹ‘ csere.",
        "IsmĂ©tlĹ‘dĂ©si sĂşly" to "Semleges 0% esetĂ©n a korĂˇbbi talĂˇlat sem elĹ‘nyt, sem hĂˇtrĂˇnyt nem ad. PozitĂ­v Ă©rtĂ©k nĂ¶veli, negatĂ­v Ă©rtĂ©k csĂ¶kkenti a korĂˇbbi talĂˇlĂłk rangpontjĂˇt."
    )
}

@Composable
private fun ClickableHeading(text: String, onClick: () -> Unit) {
    Text(text, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Blue, modifier = Modifier.clickable { onClick() })
}

@Composable
private fun StepperRow(
    label: String,
    value: Int,
    minimum: Int,
    maximum: Int,
    onStep: (Int) -> Unit,
    showHelp: (() -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            fontSize = 13.sp,
            color = if (showHelp == null) Ink else Blue,
            modifier = Modifier.weight(1f).then(if (showHelp == null) Modifier else Modifier.clickable { showHelp() })
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { onStep(-1) }, enabled = value > minimum, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp)) { Text("â’") }
            Text(value.toString(), modifier = Modifier.width(40.dp), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
            Button(onClick = { onStep(1) }, enabled = value < maximum, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp)) { Text("+") }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NumberCard(title: String, numbers: List<Int>, highlighted: Set<Int>) {
    Card {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                numbers.forEach { number -> NumberBall(number, number in highlighted, false) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RowCard(title: String, numbers: List<Int>, highlighted: Set<Int>) {
    Card {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontWeight = FontWeight.Bold)
                if (highlighted.isNotEmpty()) Text("${highlighted.size} kiemelt", color = DarkGreen, fontWeight = FontWeight.Bold)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                numbers.forEach { number -> NumberBall(number, number in highlighted, true) }
            }
        }
    }
}

@Composable
private fun NumberBall(number: Int, highlighted: Boolean, dark: Boolean) {
    val background = when {
        highlighted && dark -> DarkGreen
        highlighted -> Green
        else -> Color(0xFFE2E8F0)
    }
    Box(Modifier.size(40.dp).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
        Text(number.toString(), fontWeight = FontWeight.Black, color = if (highlighted && dark) Color.White else Ink)
    }
}

@Composable
private fun MetricCard(title: String, value: String) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(value, color = Color(0xFF475569), fontSize = 13.sp)
        }
    }
}
