package app.footor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.footor.AppContainer
import app.footor.FootorApp
import app.footor.core.live.LiveState
import app.footor.core.model.Match
import app.footor.core.model.MatchStatus
import app.footor.ui.theme.LiveRed
import coil.compose.AsyncImage
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as FootorApp).container

/** Logo or photo from the provider, with a neutral placeholder while loading or when missing. */
@Composable
fun RemoteImage(url: String?, size: Dp, modifier: Modifier = Modifier, circle: Boolean = false) {
    val placeholder = rememberVectorPainter(Icons.Default.Shield)
    AsyncImage(
        model = url,
        contentDescription = null,
        placeholder = placeholder,
        error = placeholder,
        fallback = placeholder,
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size).let { if (circle) it.clip(CircleShape) else it },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
        actions = { actions() },
    )
}

@Composable
fun CenteredMessage(text: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
            if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** Banner telling the user the data on screen may be out of date, instead of silently showing old scores. */
@Composable
fun StaleBanner(state: LiveState<*>, now: Instant = Instant.now()) {
    val error = state.error ?: return
    val age = state.lastUpdated?.let { Duration.between(it, now).seconds }
    val text = buildString {
        append(error.message ?: "Update failed")
        if (age != null) append(" · showing data from ${formatAge(age)} ago")
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.small)
            .padding(8.dp),
    )
}

private fun formatAge(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60} min"
    else -> "${seconds / 3600} h"
}

private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

fun Match.kickoffTime(zone: ZoneId = ZoneId.systemDefault()): String = timeFormat.format(kickoff.atZone(zone))

/** Short status for list rows: the live clock, "HT", "FT", or the kick-off time. */
fun Match.statusLabel(): String = when (status) {
    MatchStatus.SCHEDULED -> kickoffTime()
    MatchStatus.HALF_TIME -> "HT"
    MatchStatus.BREAK_BEFORE_EXTRA_TIME -> "Break"
    MatchStatus.BREAK_BEFORE_PENALTIES -> "Break"
    MatchStatus.PENALTIES -> "Pens"
    MatchStatus.INTERRUPTED -> "Int."
    MatchStatus.SUSPENDED -> "Susp."
    MatchStatus.FINISHED -> "FT"
    MatchStatus.FINISHED_AFTER_EXTRA_TIME -> "AET"
    MatchStatus.FINISHED_AFTER_PENALTIES -> "Pens"
    MatchStatus.AWARDED -> "Awd"
    MatchStatus.POSTPONED -> "PP"
    MatchStatus.CANCELLED -> "Canc."
    MatchStatus.ABANDONED -> "Abd"
    MatchStatus.UNKNOWN -> "-"
    else -> clock.toString().ifEmpty { "Live" }
}

@Composable
fun StatusText(match: Match, modifier: Modifier = Modifier) {
    Text(
        match.statusLabel(),
        modifier = modifier,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (match.status.isLive) FontWeight.Bold else FontWeight.Normal,
        color = if (match.status.isLive) LiveRed else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/** Placeholder until the first load completes, then the content (with a stale-data banner on errors). */
@Composable
fun <T> LiveContent(
    state: LiveState<T>,
    onRetry: () -> Unit,
    content: @Composable (T) -> Unit,
) {
    val data = state.data
    when {
        data != null -> Column {
            StaleBanner(state)
            content(data)
        }
        state.error != null -> CenteredMessage(state.error?.message ?: "Something went wrong", actionLabel = "Retry", onAction = onRetry)
        else -> Loading()
    }
}
