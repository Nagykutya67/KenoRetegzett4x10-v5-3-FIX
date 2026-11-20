package hu.eragroup.kenoretegzett

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

@Serializable
data class Draw(val date: String, val numbers: List<Int>)

@Serializable
data class AppSettings(
    val quotas: Map<String, Int> = mapOf(
        "Visszatérő" to 8,
        "Rövid meglepetés" to 10,
        "Hideg" to 6,
        "Közepes 2" to 8,
        "Közepes 3" to 8
    ),
    val shortWindow: Int = 5,
    val activityWindow: Int = 10,
    val backgroundWindow: Int = 20,
    val stabilityWindow: Int = 30,
    val maxDailyChange: Int = 12,
    val repeatWeight: Int = 0,
    val hitDistribution: Map<String, Int> = mapOf("A" to 0, "B" to 0, "C" to 0, "D" to 0),
    val refreshOnOpen: Boolean = true,
    val wifiOnly: Boolean = false
)

@Serializable
data class Snapshot(
    val sourceDate: String,
    val targetDate: String,
    val p20: List<Int>,
    val q20: List<Int>,
    val rows: Map<String, List<Int>>,
    val layers: Map<String, List<Int>>,
    val retainedByRow: Map<String, List<Int>>,
    val previousHits: List<Int>,
    val hash: String
)

@Serializable
data class Evaluation(
    val sourceDate: String,
    val targetDate: String,
    val actual: List<Int>,
    val p20: List<Int>,
    val q20: List<Int>,
    val rows: Map<String, List<Int>>,
    val rowHits: Map<String, List<Int>>,
    val totalHits: Int,
    val p20Hits: Int,
    val q20Hits: Int
)

@Serializable
data class RollStat(
    val n: Int = 0,
    val sum40: Int = 0,
    val sumP: Int = 0,
    val sumQ: Int = 0,
    val sumBest20: Int = 0,
    val sumBest10: Int = 0,
    val ge6_20: Int = 0,
    val ge8_20: Int = 0,
    val ge5_10: Int = 0,
    val ge7_10: Int = 0,
    val eq10: Int = 0
)

data class UiState(
    val loading: Boolean = false,
    val lastSync: String = "nincs",
    val targetDate: String = "",
    val snapshot: Snapshot? = null,
    val evaluation: Evaluation? = null,
    val stats30: RollStat = RollStat(),
    val stats100: RollStat = RollStat(),
    val stats500: RollStat = RollStat(),
    val settings: AppSettings = AppSettings(),
    val error: String? = null
)

class KenoViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = KenoRepository(app)
    private val mutableState = MutableStateFlow(repository.loadState())
    val state = mutableState.asStateFlow()

    init {
        scheduleWorker()
        if (mutableState.value.settings.refreshOnOpen) refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            mutableState.value = withContext(Dispatchers.IO) {
                runCatching { repository.update() }
                    .getOrElse { mutableState.value.copy(loading = false, error = it.message) }
            }
        }
    }

    fun saveSettings(settings: AppSettings) {
        repository.saveSettings(settings)
        mutableState.value = mutableState.value.copy(settings = settings)
        refresh()
    }

    private fun scheduleWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<KenoWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(getApplication()).enqueueUniquePeriodicWork(
            "keno_v5_daily",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}

class KenoWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        return runCatching {
            KenoRepository(applicationContext).update()
            Result.success()
        }.getOrElse { Result.retry() }
    }
}

class KenoRepository(private val context: Context) {
    private val preferences = context.getSharedPreferences("keno_v5", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient()
    private val rowNames = listOf("A", "B", "C", "D")

    fun loadState(): UiState {
        val snapshot = loadSnapshot()
        return UiState(
            lastSync = preferences.getString("sync", "nincs") ?: "nincs",
            targetDate = snapshot?.targetDate.orEmpty(),
            snapshot = snapshot,
            evaluation = loadEvaluation(),
            stats30 = loadStat(30),
            stats100 = loadStat(100),
            stats500 = loadStat(500),
            settings = loadSettings()
        )
    }

    fun loadSettings(): AppSettings {
        val raw = preferences.getString("settings", null) ?: return AppSettings()
        return runCatching { json.decodeFromString<AppSettings>(raw) }.getOrDefault(AppSettings())
    }

    fun saveSettings(settings: AppSettings) {
        preferences.edit().putString("settings", json.encodeToString(settings)).apply()
    }

    fun update(): UiState {
        val draws = fetchOfficialDraws()
        require(draws.size >= 100) { "Nincs elegendő hiteles húzás." }
        val latest = draws.last()
        val frozenSnapshot = loadSnapshot()
        var evaluation = loadEvaluation()
        var previousHits = loadHits()

        // A már megjelenített 4x10 tipp a célhúzás lezárásáig változtathatatlan.
        if (frozenSnapshot != null && frozenSnapshot.targetDate > latest.date) {
            validateSnapshot(frozenSnapshot)
            preferences.edit().putString("sync", LocalTime.now().withSecond(0).withNano(0).toString()).apply()
            return loadState().copy(loading = false, error = null)
        }

        if (frozenSnapshot != null && frozenSnapshot.targetDate <= latest.date) {
            validateSnapshot(frozenSnapshot)
            val targetDraw = draws.firstOrNull { it.date == frozenSnapshot.targetDate }
            val evaluatedHash = preferences.getString("last_eval_hash", "")
            if (targetDraw != null && evaluatedHash != frozenSnapshot.hash) {
                val candidate = evaluate(frozenSnapshot, targetDraw)
                validateEvaluation(candidate)
                evaluation = candidate
                previousHits = candidate.rowHits.values.flatten().distinct().sorted()
                saveEvaluation(candidate)
                saveHits(previousHits)
                appendHistory(candidate)
                preferences.edit().putString("last_eval", frozenSnapshot.targetDate)
                    .putString("last_eval_hash", frozenSnapshot.hash).apply()
            }
        }

        val settings = loadSettings()
        val snapshot = buildSnapshot(draws, settings, previousHits, frozenSnapshot)
        validateSnapshot(snapshot)
        saveSnapshot(snapshot)
        preferences.edit().putString("sync", LocalTime.now().withSecond(0).withNano(0).toString()).apply()
        return UiState(
            loading = false,
            lastSync = preferences.getString("sync", "nincs") ?: "nincs",
            targetDate = snapshot.targetDate,
            snapshot = snapshot,
            evaluation = evaluation,
            stats30 = loadStat(30), stats100 = loadStat(100), stats500 = loadStat(500),
            settings = settings
        )
    }

    private fun fetchOfficialDraws(): List<Draw> {
        val request = Request.Builder()
            .url("https://bet.szerencsejatek.hu/cmsfiles/keno.csv")
            .header("User-Agent", "Mozilla/5.0")
            .build()
        val bytes = http.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Hivatalos adatletöltési hiba: HTTP ${response.code}" }
            response.body?.bytes() ?: error("A hivatalos CSV üres.")
        }
        val text = bytes.toString(Charset.forName("windows-1250"))
        val dateRegex = Regex("(20\\d{2})[.\\-/ ]+(\\d{1,2})[.\\-/ ]+(\\d{1,2})")
        val output = linkedMapOf<String, List<Int>>()
        text.lineSequence().forEach { line ->
            val dateMatches = dateRegex.findAll(line).mapNotNull { match ->
                val date = runCatching {
                    LocalDate.of(
                        match.groupValues[1].toInt(),
                        match.groupValues[2].toInt(),
                        match.groupValues[3].toInt()
                    )
                }.getOrNull()
                date?.let { it to match.range.last }
            }.toList()
            val selectedDate = dateMatches.lastOrNull() ?: return@forEach
            val tail = line.substring(selectedDate.second + 1)
            val numbers = Regex("(?<!\\d)([1-9]|[1-7]\\d|80)(?!\\d)")
                .findAll(tail)
                .map { it.value.toInt() }
                .take(20)
                .toList()
            if (numbers.size == 20 && numbers.distinct().size == 20) {
                output[selectedDate.first.toString()] = numbers.sorted()
            }
        }
        return output.map { Draw(it.key, it.value) }.sortedBy { it.date }
    }

    private fun buildSnapshot(
        draws: List<Draw>,
        settings: AppSettings,
        previousHits: List<Int>,
        oldSnapshot: Snapshot?
    ): Snapshot {
        val history = draws.map { it.numbers.toSet() }
        val latestNumbers = history.last()

        fun countInWindow(window: Int, number: Int): Int {
            return history.takeLast(window).count { draw -> number in draw }
        }

        fun score(number: Int): Double {
            val transitionCount = history.takeLast(40).zipWithNext().count { pair ->
                number in pair.first && number in pair.second
            }
            val repeatAdjustment = if (number in previousHits) settings.repeatWeight / 10.0 else 0.0
            return countInWindow(settings.shortWindow, number) * 2.2 +
                countInWindow(settings.activityWindow, number) * 1.3 +
                countInWindow(settings.backgroundWindow, number) * 0.45 +
                countInWindow(settings.stabilityWindow, number) * 0.18 +
                transitionCount * 0.65 + repeatAdjustment
        }

        val used = mutableSetOf<Int>()
        fun select(pool: List<Int>, amount: Int): List<Int> {
            val selected = pool.filter { it !in used }
                .sortedByDescending { score(it) }
                .take(amount)
                .toMutableList()
            if (selected.size < amount) {
                val filler = (1..80).filter { it !in used && it !in selected }
                    .sortedByDescending { score(it) }
                    .take(amount - selected.size)
                selected.addAll(filler)
            }
            used.addAll(selected)
            return selected
        }

        val quotas = settings.quotas
        val layers = linkedMapOf(
            "Visszatérő" to select(latestNumbers.toList(), quotas["Visszatérő"] ?: 8),
            "Rövid meglepetés" to select(
                (1..80).filter { it !in latestNumbers && countInWindow(settings.shortWindow, it) == 0 },
                quotas["Rövid meglepetés"] ?: 10
            ),
            "Hideg" to select(
                (1..80).filter { it !in latestNumbers && countInWindow(settings.activityWindow, it) <= 1 },
                quotas["Hideg"] ?: 6
            ),
            "Közepes 2" to select(
                (1..80).filter { countInWindow(settings.activityWindow, it) == 2 },
                quotas["Közepes 2"] ?: 8
            ),
            "Közepes 3" to select(
                (1..80).filter { countInWindow(settings.activityWindow, it) == 3 },
                quotas["Közepes 3"] ?: 8
            )
        )

        val initialCandidates = layers.values.flatten().distinct().sortedByDescending { score(it) }.toMutableList()
        if (initialCandidates.size < 40) {
            initialCandidates.addAll(
                (1..80).filter { it !in initialCandidates }
                    .sortedByDescending { score(it) }
                    .take(40 - initialCandidates.size)
            )
        }

        var ranked = initialCandidates.take(40).toMutableList()
        if (oldSnapshot != null && settings.maxDailyChange < 40) {
            val keepCount = 40 - settings.maxDailyChange
            val protected = oldSnapshot.rows.values.flatten()
                .filter { it in ranked }
                .sortedByDescending { score(it) }
                .take(keepCount)
            ranked = (protected + ranked.filter { it !in protected }).distinct().take(40).toMutableList()
        }

        val requestedDistribution = settings.hitDistribution.mapValues { entry -> entry.value.coerceIn(0, 10) }
        val requestedHitCount = requestedDistribution.values.sum().coerceAtMost(previousHits.size)
        val selectedPreviousHits = previousHits.sortedByDescending { score(it) }.take(requestedHitCount)
        ranked = (selectedPreviousHits + ranked).distinct().toMutableList()
        if (ranked.size < 40) {
            ranked.addAll(
                (1..80).filter { it !in ranked }
                    .sortedByDescending { score(it) }
                    .take(40 - ranked.size)
            )
        }
        ranked = ranked.take(40).toMutableList()

        val p20 = ranked.sortedByDescending { score(it) }.take(20)
        val q20 = ranked.filter { it !in p20 }
        val outputRows = rowNames.associateWith { mutableListOf<Int>() }.toMutableMap()
        val retainedRows = rowNames.associateWith { mutableListOf<Int>() }.toMutableMap()
        val hitQueue = selectedPreviousHits.toMutableList()

        for (rowName in rowNames) {
            val amount = minOf(requestedDistribution[rowName] ?: 0, hitQueue.size)
            repeat(amount) {
                val number = hitQueue.removeAt(0)
                outputRows.getValue(rowName).add(number)
                retainedRows.getValue(rowName).add(number)
            }
        }

        fun fillPair(group: List<Int>, firstRow: String, secondRow: String) {
            val remaining = group.filter { candidate ->
                outputRows.values.none { row -> candidate in row }
            }.toMutableList()
            val pairRows = listOf(firstRow, secondRow)
            for ((rowIndex, rowName) in pairRows.withIndex()) {
                val destination = outputRows.getValue(rowName)
                while (destination.size < 10) {
                    var bestNumber: Int? = null
                    var bestValue = Double.NEGATIVE_INFINITY
                    for (candidate in remaining) {
                        var correlationPenalty = 0.0
                        for (other in destination) {
                            val together = history.count { draw -> candidate in draw && other in draw }
                            correlationPenalty += together.toDouble() / history.size.toDouble()
                        }
                        val value = score(candidate) - correlationPenalty * (1.0 + rowIndex * 0.1)
                        if (value > bestValue) {
                            bestValue = value
                            bestNumber = candidate
                        }
                    }
                    val selected = bestNumber ?: break
                    destination.add(selected)
                    remaining.remove(selected)
                }
            }
        }

        fillPair(p20, "A", "B")
        fillPair(q20, "C", "D")
        val finalRows = outputRows.mapValues { entry -> entry.value.sorted() }
        require(finalRows.values.all { it.size == 10 }) { "Valamelyik tippsor nem 10 számból áll." }
        require(finalRows.values.flatten().distinct().size == 40) { "A négy tippsor nem 40 különböző számot tartalmaz." }

        val sourceDate = draws.last().date
        val targetDate = LocalDate.parse(sourceDate).plusDays(1).toString()
        val source = sourceDate + targetDate + finalRows.values.flatten().joinToString(",")
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }

        return Snapshot(
            sourceDate = sourceDate,
            targetDate = targetDate,
            p20 = p20.sorted(),
            q20 = q20.sorted(),
            rows = finalRows,
            layers = layers,
            retainedByRow = retainedRows.mapValues { entry -> entry.value.sorted() },
            previousHits = previousHits.sorted(),
            hash = hash
        )
    }

    private fun validateSnapshot(snapshot: Snapshot) {
        require(snapshot.rows.keys.containsAll(rowNames)) { "Hiányzik egy vagy több tippsor (A-D)." }
        require(rowNames.all { snapshot.rows[it]?.size == 10 }) { "Pontosan 4x10 szám szükséges." }
        require(snapshot.rows.values.flatten().distinct().size == 40) { "A négy sorban 40 különböző szám szükséges." }
        require(snapshot.p20.size == 20 && snapshot.q20.size == 20) { "A P20 és Q20 mérete hibás." }
        require(snapshot.p20.toSet().intersect(snapshot.q20.toSet()).isEmpty()) { "A P20 és Q20 átfed." }
        require(snapshot.rows.getValue("A").toSet() + snapshot.rows.getValue("B").toSet() == snapshot.p20.toSet()) { "A+B nem egyezik P20-szal." }
        require(snapshot.rows.getValue("C").toSet() + snapshot.rows.getValue("D").toSet() == snapshot.q20.toSet()) { "C+D nem egyezik Q20-szal." }
    }

    private fun validateEvaluation(evaluation: Evaluation) {
        require(evaluation.actual.size == 20 && evaluation.actual.distinct().size == 20) { "A hivatalos húzás nem 20 különböző szám." }
        require(rowNames.all { evaluation.rows[it]?.size == 10 }) { "Az értékelés nem 4x10 sorból készült." }
        val rowTotal = rowNames.sumOf { evaluation.rowHits[it].orEmpty().size }
        require(rowTotal == evaluation.totalHits) { "A soronkénti és összes találat eltér." }
        require(evaluation.p20Hits + evaluation.q20Hits == evaluation.totalHits) { "A 20-as és 40-es összesítés eltér." }
        require(evaluation.rowHits.getValue("A").size + evaluation.rowHits.getValue("B").size == evaluation.p20Hits) { "A+B találata nem egyezik P20-szal." }
        require(evaluation.rowHits.getValue("C").size + evaluation.rowHits.getValue("D").size == evaluation.q20Hits) { "C+D találata nem egyezik Q20-szal." }
    }

    private fun evaluate(snapshot: Snapshot, draw: Draw): Evaluation {
        val actualSet = draw.numbers.toSet()
        val rowHits = snapshot.rows.mapValues { entry -> entry.value.filter { it in actualSet } }
        return Evaluation(
            sourceDate = snapshot.sourceDate,
            targetDate = snapshot.targetDate,
            actual = draw.numbers,
            p20 = snapshot.p20,
            q20 = snapshot.q20,
            rows = snapshot.rows,
            rowHits = rowHits,
            totalHits = rowHits.values.sumOf { it.size },
            p20Hits = snapshot.p20.count { it in actualSet },
            q20Hits = snapshot.q20.count { it in actualSet }
        )
    }

    private fun loadSnapshot(): Snapshot? {
        val raw = preferences.getString("snapshot", null) ?: return null
        return runCatching { json.decodeFromString<Snapshot>(raw) }.getOrNull()
    }

    private fun saveSnapshot(snapshot: Snapshot) {
        preferences.edit().putString("snapshot", json.encodeToString(snapshot)).apply()
    }

    private fun loadEvaluation(): Evaluation? {
        val raw = preferences.getString("evaluation", null) ?: return null
        return runCatching { json.decodeFromString<Evaluation>(raw) }.getOrNull()
    }

    private fun saveEvaluation(evaluation: Evaluation) {
        preferences.edit().putString("evaluation", json.encodeToString(evaluation)).apply()
    }

    private fun loadHits(): List<Int> {
        return preferences.getString("hits", "").orEmpty()
            .split(',')
            .mapNotNull { it.toIntOrNull() }
    }

    private fun saveHits(hits: List<Int>) {
        preferences.edit().putString("hits", hits.joinToString(",")).apply()
    }

    private fun appendHistory(evaluation: Evaluation) {
        val history = loadHistory().toMutableList()
        history.add(evaluation)
        preferences.edit().putString("history", json.encodeToString(history.takeLast(500))).apply()
    }

    private fun loadHistory(): List<Evaluation> {
        val raw = preferences.getString("history", null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<Evaluation>>(raw) }.getOrDefault(emptyList())
    }

    private fun loadStat(window: Int): RollStat {
        val evaluations = loadHistory().takeLast(window)
        if (evaluations.isEmpty()) return RollStat()
        var sum40 = 0
        var sumP20 = 0
        var sumQ20 = 0
        var sumBest20 = 0
        var sumBest10 = 0
        var count6Plus20 = 0
        var count8Plus20 = 0
        var count5Plus10 = 0
        var count7Plus10 = 0
        var count10 = 0
        for (evaluation in evaluations) {
            val best20 = maxOf(evaluation.p20Hits, evaluation.q20Hits)
            val best10 = evaluation.rowHits.values.map { it.size }.maxOrNull() ?: 0
            sum40 += evaluation.totalHits
            sumP20 += evaluation.p20Hits
            sumQ20 += evaluation.q20Hits
            sumBest20 += best20
            sumBest10 += best10
            if (best20 >= 6) count6Plus20++
            if (best20 >= 8) count8Plus20++
            if (best10 >= 5) count5Plus10++
            if (best10 >= 7) count7Plus10++
            if (best10 == 10) count10++
        }
        return RollStat(
            n = evaluations.size,
            sum40 = sum40,
            sumP = sumP20,
            sumQ = sumQ20,
            sumBest20 = sumBest20,
            sumBest10 = sumBest10,
            ge6_20 = count6Plus20,
            ge8_20 = count8Plus20,
            ge5_10 = count5Plus10,
            ge7_10 = count7Plus10,
            eq10 = count10
        )
    }
}
