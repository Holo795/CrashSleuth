package dev.holo795.crashsleuth.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.holo795.crashsleuth.app.ShareLink

/**
 * A report someone else shared, read from its link: the same reading as a report of this computer, but
 * nothing can be launched from it since the files are not here.
 */
@Composable
fun SharedScreen(state: AppState, shared: ShareLink.Shared) {
    val ui = state.ui
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(Icons.Back, state::home)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(shared.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ui["shared.from"], style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted)
            }
        }
        Spacer(Modifier.height(32.dp))
        Column(Modifier.widthIn(max = 720.dp)) {
            Facts(shared.facts)
            Spacer(Modifier.height(16.dp))
            val first = shared.findings.firstOrNull()
            if (first == null) {
                Text(ui["report.none.title"], style = MaterialTheme.typography.headlineMedium)
            } else {
                Text(first.title, style = MaterialTheme.typography.headlineMedium)
                if (first.culprits.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Overline(if (first.culprits.size > 1) ui["report.culprits"] else ui["report.culprit"])
                    Spacer(Modifier.height(8.dp))
                    first.culprits.forEach { culprit ->
                        Text(listOfNotNull(culprit.name, culprit.version).joinToString(" "), style = MaterialTheme.typography.headlineSmall, color = Theme.tones.critical)
                    }
                }
                Spacer(Modifier.height(24.dp))
                Overline(ui["report.whatToDo"])
                Spacer(Modifier.height(8.dp))
                Text(first.advice, style = MaterialTheme.typography.bodyLarge)
                if (first.evidence.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    CodeBlock(first.evidence)
                }
            }
            val others = shared.findings.drop(1)
            if (others.isNotEmpty()) {
                Spacer(Modifier.height(40.dp))
                Overline(ui["report.others"])
                others.forEach { item ->
                    Spacer(Modifier.height(12.dp))
                    Text(item.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        (item.culprits.map { it.name } + item.advice).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (shared.installed.isNotEmpty()) {
                Spacer(Modifier.height(40.dp))
                Overline(ui["report.content"])
                Spacer(Modifier.height(8.dp))
                Text(shared.installed.joinToString(" · ") { listOfNotNull(it.name, it.version).joinToString(" ") }, style = MaterialTheme.typography.bodySmall, color = Theme.tones.muted)
            }
        }
    }
}
