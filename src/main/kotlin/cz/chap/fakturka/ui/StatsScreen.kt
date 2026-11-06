package cz.chap.fakturka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cz.chap.fakturka.domain.formatAmount
import cz.chap.fakturka.domain.money
import cz.chap.fakturka.i18n.Messages
import cz.chap.fakturka.stats.PeriodTotal
import cz.chap.fakturka.stats.StatsPeriod
import cz.chap.fakturka.storage.DataRepository
import cz.chap.fakturka.ui.state.StatsState
import cz.chap.fakturka.ui.state.runCatchingUi
import cz.chap.fakturka.ui.state.uiMessage
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun StatsScreen(repo: DataRepository) {
    val loaded = remember(repo) { runCatchingUi { StatsState(repo) } }
    loaded.exceptionOrNull()?.let { error ->
        Column(Modifier.fillMaxSize().padding(24.dp)) { ErrorBanner(error.uiMessage()) }
        return
    }
    val state = loaded.getOrThrow()
    var period by remember { mutableStateOf(StatsPeriod.MONTH) }
    var year by remember(state) { mutableStateOf(state.year) }
    val totals = remember(state, period, year) { state.year = year; state.totals(period) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(Messages.get("ui.nav.stats"), style = MaterialTheme.typography.headlineMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SingleChoiceSegmentedButtonRow {
                StatsPeriod.entries.forEachIndexed { i, p ->
                    SegmentedButton(
                        selected = p == period,
                        onClick = { period = p },
                        shape = SegmentedButtonDefaults.itemShape(i, StatsPeriod.entries.size),
                    ) { Text(Messages.get("ui.stats.period.${p.name.lowercase()}")) }
                }
            }
            if (period != StatsPeriod.YEAR) {
                val index = state.years.indexOf(year)
                TextButton(onClick = { year = state.years[index - 1] }, enabled = index > 0) { Text("‹") }
                Text(year.toString(), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { year = state.years[index + 1] }, enabled = index < state.years.lastIndex) { Text("›") }
            }
        }

        val sum = totals.fold(BigDecimal.ZERO) { acc, t -> acc + t.base }.money()
        Text(
            Messages.get("ui.stats.sum").format(formatAmount(sum), totals.sumOf { it.count }),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(Messages.get("ui.stats.note"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (totals.isEmpty()) Text(Messages.get("ui.stats.empty"))
        else BarChart(totals) { label(period, it) }
    }
}

private fun label(period: StatsPeriod, p: PeriodTotal): String = when (period) {
    StatsPeriod.MONTH -> Messages.get("ui.stats.month.${p.period}")
    StatsPeriod.QUARTER -> "Q${p.period}"
    StatsPeriod.YEAR -> p.period.toString()
}

/** Vertical bars with the amount above each bar and the period below; amounts are rounded to whole crowns. */
@Composable
private fun BarChart(totals: List<PeriodTotal>, label: (PeriodTotal) -> String) {
    val max = totals.maxOf { it.base }.takeIf { it.signum() > 0 } ?: BigDecimal.ONE
    val chartHeight = 260.dp
    OutlinedCard(Modifier.widthIn(max = 1000.dp).fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            totals.forEach { t ->
                val fraction = t.base.coerceAtLeast(BigDecimal.ZERO).divide(max, 4, RoundingMode.HALF_UP).toFloat()
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (t.count == 0) "" else formatAmount(t.base).substringBefore('.'),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.height(chartHeight), contentAlignment = Alignment.BottomCenter) {
                        Box(
                            Modifier.fillMaxWidth(0.7f)
                                .fillMaxHeight(fraction.coerceAtLeast(if (t.count > 0) 0.005f else 0f))
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                        )
                    }
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                    Text(label(t), style = MaterialTheme.typography.labelMedium)
                    Text(
                        if (t.count == 0) "" else Messages.get("ui.stats.count").format(t.count),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
