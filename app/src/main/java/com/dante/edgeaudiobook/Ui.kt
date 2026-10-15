package com.dante.edgeaudiobook

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun App(vm: Vm = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(vm.prefs.tab.coerceIn(0, 4)) }
    LaunchedEffect(tab) { vm.prefs.tab = tab }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            snack.showSnackbar(it)
            vm.message = null
        }
    }
    val tabs = listOf(
        "Text" to Icons.Default.Search,
        "Chapters" to Icons.Default.Menu,
        "Voice" to Icons.Default.Face,
        "Speech" to Icons.Default.Settings,
        "Export" to Icons.Default.PlayArrow
    )
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(icon, label) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize()) {
            when (tab) {
                0 -> TextScreen(vm)
                1 -> ChaptersScreen(vm) { tab = 0 }
                2 -> VoiceScreen(vm)
                3 -> PronounceScreen(vm)
                else -> ExportScreen(vm)
            }
        }
    }
}

@Composable
private fun Centered(msg: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(msg, textAlign = TextAlign.Center)
    }
}

// ============================ TEXT / READER ============================

private fun firstMatchFrom(r: List<IntRange>, pos: Int): Int {
    var lo = 0
    var hi = r.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (r[mid].last < pos) lo = mid + 1 else hi = mid
    }
    return lo
}

@Composable
private fun BlockView(vm: Vm, text: String, absStart: Int) {
    val ranges = vm.matches.ranges
    val cur = vm.matchIdx
    val annotated = remember(text, absStart, vm.matches, cur) {
        buildAnnotatedString {
            append(text)
            var k = firstMatchFrom(ranges, absStart)
            while (k < ranges.size && ranges[k].first < absStart + text.length) {
                val s = (ranges[k].first - absStart).coerceAtLeast(0)
                val e = (ranges[k].last + 1 - absStart).coerceAtMost(text.length)
                if (e > s) {
                    addStyle(
                        SpanStyle(
                            background = if (k == cur) Color(0xFFFFB74D) else Color(0xFFFFF59D),
                            color = Color.Black
                        ),
                        s, e
                    )
                }
                k++
            }
        }
    }
    var sel by remember(text) { mutableStateOf(TextRange.Zero) }
    BasicTextField(
        value = TextFieldValue(annotated, sel),
        onValueChange = { nv ->
            if (nv.text == text) {
                sel = nv.selection
                vm.selStart = absStart + nv.selection.min
                vm.selEnd = absStart + nv.selection.max
            }
        },
        readOnly = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

@Composable
fun TextScreen(vm: Vm) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) vm.loadBook(u)
    }
    val b = vm.book
    if (b == null) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Edge Audiobook", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Turn a .txt, .md or .epub into one audio file per chapter using Edge neural voices.",
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !vm.loading) {
                Text(if (vm.loading) "Loading…" else "Open a book")
            }
        }
        return
    }

    val blocks = vm.blocks
    val listState = vm.listState
    val scope = rememberCoroutineScope()
    var mark by remember { mutableStateOf(false) }
    val names = remember(vm.chapters.toList(), blocks) {
        val m = HashMap<Int, MutableList<String>>()
        for (c in vm.chapters) m.getOrPut(TextOps.blockIndex(blocks, c.start)) { mutableListOf() }.add(c.name)
        m
    }
    LaunchedEffect(vm.jumpOffset, blocks) {
        val off = vm.jumpOffset
        if (off != null && blocks.isNotEmpty()) {
            listState.scrollToItem(TextOps.blockIndex(blocks, off))
            vm.jumpOffset = null
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(
                    "${b.words} words · ~${TextOps.minutes(b.words)} min of audio",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Open…") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = vm.searchQuery,
                onValueChange = { vm.setQuery(it) },
                label = { Text("Search in text") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.stepMatch(1) })
            )
            Text(
                "${if (vm.matches.ranges.isEmpty()) 0 else vm.matchIdx + 1}/${vm.matches.ranges.size}",
                modifier = Modifier.padding(horizontal = 6.dp),
                style = MaterialTheme.typography.labelMedium
            )
            TextButton(onClick = { vm.stepMatch(-1) }) { Text("▲") }
            TextButton(onClick = { vm.stepMatch(1) }) { Text("▼") }
        }
        Card(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(blocks.size) { i ->
                    val start = blocks[i]
                    val end = if (i + 1 < blocks.size) blocks[i + 1] else b.text.length
                    val t = remember(b, i) { b.text.substring(start, end).trimEnd() }
                    Column {
                        names[i]?.forEach { n ->
                            Text(
                                "▸ $n",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 12.dp, top = 8.dp)
                            )
                        }
                        BlockView(vm, t, start)
                    }
                }
            }
        }
        val total = (blocks.size - 1).coerceAtLeast(1)
        val here = listState.firstVisibleItemIndex.coerceIn(0, total)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${here * 100 / total}%", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = here.toFloat(),
                onValueChange = { x -> scope.launch { listState.scrollToItem(x.roundToInt()) } },
                valueRange = 0f..total.toFloat(),
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
            TextButton(onClick = { mark = !mark }) { Text(if (mark) "Mark ▴" else "Mark ▾") }
        }
        if (mark) {
            Card(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text("Long-press to select text above, then:", style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val s = vm.selStart
                            if (s == null) vm.message = "Select or tap some text first" else vm.draftStart = s
                        }) { Text("Start here") }
                        Button(onClick = {
                            val e = vm.selEnd
                            if (e == null) vm.message = "Select or tap some text first" else vm.draftEnd = e
                        }) { Text("End here") }
                    }
                    Text("Start: ${vm.snippet(vm.draftStart)}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("End: ${vm.snippet(vm.draftEnd, forward = false)}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = vm.draftName,
                            onValueChange = { vm.draftName = it },
                            label = { Text("Chapter name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { vm.addDraft() }, enabled = vm.draftStart != null) { Text("Add") }
                    }
                }
            }
        }
    }
}

// ============================ CHAPTERS ============================

@Composable
fun ChaptersScreen(vm: Vm, onGoto: () -> Unit) {
    val b = vm.book
    if (b == null) {
        Centered("Open a book first (Text tab).")
        return
    }
    var showToc by remember { mutableStateOf(false) }
    var showAuto by remember { mutableStateOf(false) }
    var showPhrase by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<Chapter?>(null) }
    val spans = TextOps.spans(vm.chapters, b.text.length)
    val selectedWords = remember(spans, b) {
        spans.filter { it.ch.include }.sumOf { TextOps.countWords(b.text, it.start, it.end) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showToc = true }, modifier = Modifier.weight(1f)) { Text("From contents") }
            Button(onClick = { showPhrase = true }, modifier = Modifier.weight(1f)) { Text("By text") }
            OutlinedButton(onClick = { vm.chapters.clear() }, modifier = Modifier.weight(1f)) { Text("Clear") }
        }
        Text(
            "${spans.size} sections · ${spans.count { it.ch.include }} selected · $selectedWords words (~${TextOps.minutes(selectedWords)} min)",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(vertical = 6.dp)
        )
        if (spans.isEmpty()) {
            Centered("No chapters yet.\nUse “From contents”, “By text”, or mark them in the Text tab.")
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(spans, key = { it.ch.id }) { s ->
                    val c = s.ch
                    val words = remember(b, s.start, s.end) { TextOps.countWords(b.text, s.start, s.end) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = c.include, onCheckedChange = { v -> vm.edit(c.id) { it.copy(include = v) } })
                        Column(
                            Modifier.weight(1f).clickable { vm.jumpOffset = s.start; onGoto() }
                        ) {
                            Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            Text("$words words · ~${TextOps.minutes(words)} min", style = MaterialTheme.typography.labelSmall)
                            Text(
                                vm.snippet(s.start),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { rename = c }) { Icon(Icons.Default.Edit, "Rename") }
                        IconButton(onClick = { vm.remove(c.id) }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (showToc) TocDialog(vm, onPattern = { showToc = false; showAuto = true }) { showToc = false }
    if (showAuto) AutoDialog(vm) { showAuto = false }
    if (showPhrase) PhraseDialog(vm) { showPhrase = false }
    rename?.let { c -> RenameDialog(c, onSave = { n -> vm.edit(c.id) { it.copy(name = n) } }) { rename = null } }
}

@Composable
private fun TocDialog(vm: Vm, onPattern: () -> Unit, onDismiss: () -> Unit) {
    val b = vm.book ?: return
    val maxLevel = vm.tocLevels()
    var level by remember { mutableIntStateOf(minOf(maxLevel, 2).coerceAtLeast(1)) }
    var minWords by remember { mutableStateOf("15") }
    var replace by remember { mutableStateOf(true) }
    val count = remember(level, minWords, b) { vm.tocPreview(level, minWords.toIntOrNull() ?: 15).size }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chapters from the table of contents") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (b.toc.isEmpty()) {
                    Text(
                        "No table of contents was found in this file (no EPUB contents, no “Contents” list, no headings). " +
                            "Use “By text”, mark chapters in the Text tab, or try the pattern scan.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text("Source: ${b.tocSource} · ${b.toc.size} entries", style = MaterialTheme.typography.bodyMedium)
                    if (maxLevel > 1) {
                        Text("Levels included: 1 to $level of $maxLevel")
                        Slider(
                            value = level.toFloat(),
                            onValueChange = { x -> level = x.roundToInt().coerceIn(1, maxLevel) },
                            valueRange = 1f..maxLevel.toFloat(),
                            steps = (maxLevel - 2).coerceAtLeast(0)
                        )
                    }
                    OutlinedTextField(
                        value = minWords, onValueChange = { minWords = it.filter(Char::isDigit) },
                        label = { Text("Skip entries with fewer words than") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = replace, onCheckedChange = { replace = it })
                        Text("Replace existing chapters")
                    }
                    Text("→ $count chapters", fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = onPattern) { Text("Scan by pattern instead (advanced)") }
            }
        },
        confirmButton = {
            TextButton(
                enabled = b.toc.isNotEmpty() && count > 0,
                onClick = { vm.applyToc(level, minWords.toIntOrNull() ?: 15, replace); onDismiss() }
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun AutoDialog(vm: Vm, onDismiss: () -> Unit) {
    var rx by remember { mutableStateOf(TextOps.DEFAULT_HEADING_REGEX) }
    var minWords by remember { mutableStateOf("50") }
    var replace by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pattern scan") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Advanced: finds lines like “Chapter 3”, “Part II”, “# Title” by pattern. " +
                        "Prefer “From contents” when the book has a table of contents.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = rx, onValueChange = { rx = it },
                    label = { Text("Heading regex (per line, case-insensitive)") },
                    minLines = 3, maxLines = 6
                )
                OutlinedTextField(
                    value = minWords, onValueChange = { minWords = it.filter(Char::isDigit) },
                    label = { Text("Ignore sections shorter than (words)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = replace, onCheckedChange = { replace = it })
                    Text("Replace existing chapters")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.autoDetect(rx, minWords.toIntOrNull() ?: 50, replace); onDismiss() }) { Text("Scan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PhraseDialog(vm: Vm, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var s by remember { mutableStateOf("") }
    var e by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add chapter by text") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Chapter name") }, singleLine = true)
                OutlinedTextField(s, { s = it }, label = { Text("Starts at (first words)") }, minLines = 2)
                OutlinedTextField(e, { e = it }, label = { Text("Ends at (last words) — optional") }, minLines = 2)
                Text(
                    "Matching ignores case and line breaks. If the end is empty, the chapter runs until the next chapter starts.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (vm.addByPhrases(name, s, e)) onDismiss() }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RenameDialog(c: Chapter, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var n by remember { mutableStateOf(c.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = { OutlinedTextField(n, { n = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(n.ifBlank { c.name }); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ============================ VOICE ============================

private fun signed(n: Int) = String.format(Locale.US, "%+d", n)

@Composable
fun VoiceScreen(vm: Vm) {
    var q by remember { mutableStateOf("") }
    val list = remember(vm.voices, q) {
        val t = q.trim().lowercase()
        if (t.isEmpty()) vm.voices
        else vm.voices.filter { (it.shortName + " " + it.friendly + " " + it.gender).lowercase().contains(t) }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Voice settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
                Text("Speed: ${signed(vm.rate)}%")
                Slider(
                    value = vm.rate.toFloat(),
                    onValueChange = { v -> vm.update { rate = (v / 5).roundToInt() * 5 } },
                    valueRange = -50f..100f
                )
                Text("Pitch: ${signed(vm.pitch)} Hz")
                Slider(
                    value = vm.pitch.toFloat(),
                    onValueChange = { v -> vm.update { pitch = (v / 5).roundToInt() * 5 } },
                    valueRange = -50f..50f
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = vm.readTitle, onCheckedChange = { v -> vm.update { readTitle = v } })
                    Spacer(Modifier.width(8.dp))
                    Text("Read the chapter title first")
                }
                OutlinedTextField(
                    value = vm.sample, onValueChange = { vm.sample = it },
                    label = { Text("Preview text") }, modifier = Modifier.fillMaxWidth()
                )
                Button(onClick = { vm.preview() }, enabled = !vm.previewing) {
                    Text(if (vm.previewing) "Working…" else "Preview: ${vm.voice}")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = q, onValueChange = { q = it },
                        label = { Text("Search ${vm.voices.size} voices") },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { vm.loadVoices() }, enabled = !vm.voicesLoading) {
                        Text(if (vm.voicesLoading) "…" else "Reload")
                    }
                }
            }
        }
        items(list, key = { it.shortName }) { v ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.update { voice = v.shortName } }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = vm.voice == v.shortName, onClick = null)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(v.shortName, fontWeight = FontWeight.SemiBold)
                    Text("${v.gender} · ${v.friendly}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { vm.update { voice = v.shortName }; vm.preview() }, enabled = !vm.previewing) { Text("Play") }
            }
            HorizontalDivider()
        }
        item {
            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Advanced", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = vm.chromium, onValueChange = { v -> vm.update { chromium = v.trim() } },
                    label = { Text("Edge client version") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "If every request starts failing with HTTP 403, Microsoft may have raised the required " +
                        "version. Put the current Microsoft Edge version number here (e.g. 143.0.3650.75).",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

// ============================ PRONUNCIATION ============================

private fun modeLabel(m: Int) = when (m) {
    PronRule.PATTERN -> "pattern"
    PronRule.REGEX -> "regex"
    PronRule.SKIP -> "skip text"
    PronRule.SECTION -> "skip section"
    else -> "word"
}

@Composable
private fun IntSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Text("$label: ${signed(value)}")
    Slider(
        value = value.toFloat(),
        onValueChange = { x -> onChange(x.roundToInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat()
    )
}

@Composable
private fun EmphasisSection(vm: Vm) {
    val e = vm.emph
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Emphasis", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = e.enabled, onCheckedChange = { v -> vm.updateEmph(e.copy(enabled = v)) })
            Spacer(Modifier.width(8.dp))
            Text("Stress bold and italic words (from .md / .epub markup)")
        }
        Text("How to apply it", fontWeight = FontWeight.SemiBold)
        listOf(
            0 to "Nested speed / pitch / volume tags",
            1 to "Emphasis tag",
            2 to "Separate requests, stitched together"
        ).forEach { (m, label) ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.updateEmph(e.copy(method = m)) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = e.method == m, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(label)
            }
        }
        IntSlider("Bold speed (%)", e.boldRate, -30..30) { vm.updateEmph(e.copy(boldRate = it)) }
        IntSlider("Bold pitch (Hz)", e.boldPitch, -20..30) { vm.updateEmph(e.copy(boldPitch = it)) }
        IntSlider("Bold volume (%)", e.boldVol, 0..40) { vm.updateEmph(e.copy(boldVol = it)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = e.italicOn, onCheckedChange = { v -> vm.updateEmph(e.copy(italicOn = v)) })
            Spacer(Modifier.width(8.dp))
            Text("Italic counts as emphasis")
        }
        if (e.italicOn) {
            IntSlider("Italic speed (%)", e.italicRate, -30..30) { vm.updateEmph(e.copy(italicRate = it)) }
            IntSlider("Italic pitch (Hz)", e.italicPitch, -20..30) { vm.updateEmph(e.copy(italicPitch = it)) }
            IntSlider("Italic volume (%)", e.italicVol, 0..40) { vm.updateEmph(e.copy(italicVol = it)) }
        }
        Text("Only stress spans of up to ${e.maxWords} words")
        Slider(
            value = e.maxWords.toFloat(),
            onValueChange = { x -> vm.updateEmph(e.copy(maxWords = x.roundToInt())) },
            valueRange = 1f..12f
        )
        listOf(0 to "Read bold numbers (verse numbers) normally", 1 to "Skip bold / italic numbers").forEach { (m, label) ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.updateEmph(e.copy(numberSpans = m)) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = e.numberSpans == m, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(label)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = e.skipForeign, onCheckedChange = { v -> vm.updateEmph(e.copy(skipForeign = v)) })
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Skip Hebrew / Greek / Arabic… text")
                Text("English voices cannot read other scripts. Turn off for non-Latin books.", style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
fun PronounceScreen(vm: Vm) {
    var test by remember {
        mutableStateOf("Isa 9:6; 1 Sam 2:6; Gen 5:10; Heb 1:3, 13; 5:5-10. Ps 110:5-7 (LXX). See v. 5 and vv. 5-7.")
    }
    var editing by remember { mutableStateOf<PronRule?>(null) }
    var adding by remember { mutableStateOf(false) }
    val rulesNow = vm.rules.toList()
    val output = remember(test, rulesNow, vm.builtinRefs, vm.skipRefs, vm.skipSuper) {
        Pronouncer(rulesNow, vm.builtinRefs, vm.skipRefs, vm.skipSuper).apply(test)
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { EmphasisSection(vm) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pronunciation", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = vm.builtinRefs, onCheckedChange = { v -> vm.setBuiltin(v) })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Built-in scripture reading", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Ps 110:5-7 → Psalms chapter 110 verses 5 to 7 · Isa 9:6 → Isaiah chapter 9 verse 6 · " +
                                "1 Sam → First Samuel · v. 5 → verse 5 · vv. 5-7 → verses 5 to 7 · LXX → Septuagint " +
                                "(also MT, DSS, OT, NT, cf., e.g., i.e.)",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = vm.skipRefs, onCheckedChange = { v -> vm.setSkip(refs = v) })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Skip scripture references", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Josh 3:20 · Mal 1:10 · 1 John 2:10 · (Ps 2:9-12; Isa 9:6) … are left out of the audio instead of being read.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = vm.skipSuper, onCheckedChange = { v -> vm.setSkip(sup = v) })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Skip reference numbering", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Footnote / superscript numbers such as ¹ ² ³, ^4^, [5] and [^6] are not read.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                OutlinedTextField(
                    value = test, onValueChange = { test = it },
                    label = { Text("Try it: type text with references") },
                    modifier = Modifier.fillMaxWidth()
                )
                Card(Modifier.fillMaxWidth()) {
                    Text(output, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = { vm.preview(test) }, enabled = !vm.previewing) {
                    Text(if (vm.previewing) "Working…" else "Hear it")
                }
                HorizontalDivider()
                Text("My rules", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Rules run top to bottom after the built-in reading. A “word” rule (e.g. Ma → Al Ma'edah) also " +
                        "works as a book name, so “Ma 2:5-7” becomes “Al Ma'edah chapter 2 verses 5 to 7”.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { adding = true }) { Text("Add rule") }
                    OutlinedButton(onClick = { vm.addExampleRules() }) { Text("12:3-5 rules") }
                    OutlinedButton(onClick = { vm.addSkipNotesRule() }) { Text("Skip notes") }
                }
            }
        }
        items(vm.rules.toList(), key = { it.id }) { r ->
            val err = Pron.validate(r)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = r.enabled, onCheckedChange = { v -> vm.upsertRule(r.copy(enabled = v)) })
                Column(Modifier.weight(1f)) {
                    Text(r.find, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (r.mode == PronRule.SKIP || r.mode == PronRule.SECTION) "→ skipped" else "→ " + r.say,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        modeLabel(r.mode) + (if (r.matchCase) " · match case" else "") + (if (err != null) " · invalid: $err" else ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (err != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { editing = r }) { Icon(Icons.Default.Edit, "Edit") }
                IconButton(onClick = { vm.deleteRule(r.id) }) { Icon(Icons.Default.Delete, "Delete") }
            }
            HorizontalDivider()
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    if (adding) RuleDialog(null, onSave = { vm.upsertRule(it) }) { adding = false }
    editing?.let { r -> RuleDialog(r, onSave = { vm.upsertRule(it) }) { editing = null } }
}

@Composable
private fun RuleDialog(initial: PronRule?, onSave: (PronRule) -> Unit, onDismiss: () -> Unit) {
    val id = remember { initial?.id ?: IdGen.next() }
    var find by remember { mutableStateOf(initial?.find ?: "") }
    var say by remember { mutableStateOf(initial?.say ?: "") }
    var mode by remember { mutableIntStateOf(initial?.mode ?: PronRule.WORD) }
    var cs by remember { mutableStateOf(initial?.matchCase ?: false) }
    val draft = PronRule(id, find.trim(), say, mode, cs, initial?.enabled ?: true)
    val err = if (find.isBlank()) null else Pron.validate(draft)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New rule" else "Edit rule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    find, { find = it },
                    label = {
                        Text(
                            when (mode) {
                                PronRule.SECTION -> "Section starts at (regex)"
                                PronRule.SKIP -> "Skip text matching (regex)"
                                else -> "Find"
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (mode != PronRule.SKIP) {
                    OutlinedTextField(
                        say, { say = it },
                        label = { Text(if (mode == PronRule.SECTION) "Section ends at (regex, optional)" else "Say") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                listOf(
                    PronRule.WORD to "Word or phrase",
                    PronRule.PATTERN to "Pattern (# = a number)",
                    PronRule.REGEX to "Regex",
                    PronRule.SKIP to "Skip text (regex)",
                    PronRule.SECTION to "Skip a section (start → end regex)"
                ).forEach { (m, label) ->
                    Row(Modifier.fillMaxWidth().clickable { mode = m }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == m, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(label)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = cs, onCheckedChange = { cs = it })
                    Text("Match case")
                }
                Text(
                    when (mode) {
                        PronRule.PATTERN -> "# matches a number. In “Say”, # (or #1, #2…) is replaced by the matched numbers in order. " +
                            "Example: find #:#-#  say chapter #, verses # to #"
                        PronRule.REGEX -> "A full regular expression. In “Say” use \$1, \$2… for captured groups."
                        PronRule.SKIP -> "Everything matching the regex is removed before reading. " +
                            "Example: \\[\\d+\\] removes [12]. Example: \\(see [^)]*\\) removes “(see …)”."
                        PronRule.SECTION -> "Paragraphs are skipped from the first one matching the start regex through the first one " +
                            "matching the end regex (or to the end of the chapter if no end is given). " +
                            "Example: ^(Notes|Footnotes)\$ with an empty end skips everything after a Notes heading."
                        else -> "Replaces the whole word or phrase everywhere. If it is followed by a reference like 2:5-7, " +
                            "it is read as a book name."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                if (err != null) Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = find.isNotBlank() && err == null,
                onClick = { onSave(draft); onDismiss() }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ============================ EXPORT ============================

@Composable
fun ExportScreen(vm: Vm) {
    val ctx = LocalContext.current
    val items by Jobs.items.collectAsState()
    val running by Jobs.running.collectAsState()
    var cacheBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(running) {
        cacheBytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ChunkCache.size(ctx) }
    }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            vm.update { treeUri = uri.toString() }
        }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.startExport(ctx)
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u: Uri? ->
        if (u != null) vm.pickCover(u)
    }

    val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
    var unrestricted by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        unrestricted = pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    val exampleBase = TextOps.safe(
        vm.template.ifBlank { "{n} - {title}" }
            .replace("{n}", "01").replace("{title}", "Chapter One").replace("{book}", vm.book?.title ?: "Book")
    )

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Export", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
                Text(
                    "Output location: " + (vm.treeUri?.let { Uri.parse(it).lastPathSegment } ?: "Downloads/<folder name>"),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { treePicker.launch(null) }) { Text("Choose folder…") }
                    if (vm.treeUri != null) {
                        TextButton(onClick = { vm.update { treeUri = null } }) { Text("Use Downloads") }
                    }
                }
                if (vm.treeUri == null) {
                    OutlinedTextField(
                        value = vm.folder, onValueChange = { v -> vm.update { folder = v } },
                        label = { Text("Folder name inside Downloads (empty = book title)") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = vm.template, onValueChange = { v -> vm.update { template = v } },
                    label = { Text("File name template: {n} {title} {book}") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = vm.ext, onValueChange = { v -> vm.update { ext = v } },
                    label = { Text("Extension (data is always MP3)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Text("Example: $exampleBase.${vm.ext.trim().trimStart('.').ifBlank { "m4a" }}", style = MaterialTheme.typography.bodySmall)
                Text("Album", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val bmp = vm.coverBitmap
                    if (bmp != null) Image(bitmap = bmp, contentDescription = "Album photo", modifier = Modifier.size(96.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = { photoPicker.launch("image/*") }) {
                            Text(if (bmp == null) "Choose album photo…" else "Change photo…")
                        }
                        if (bmp != null) TextButton(onClick = { vm.clearCover() }) { Text("Remove photo") }
                    }
                }
                if (vm.bookCover != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = vm.useEpubCover, onCheckedChange = { v -> vm.update { useEpubCover = v } })
                        Spacer(Modifier.width(8.dp))
                        Text("Use the EPUB's own cover when no photo is chosen")
                    }
                }
                OutlinedTextField(
                    value = vm.artist, onValueChange = { v -> vm.update { artist = v } },
                    label = { Text("Artist / author (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = vm.album, onValueChange = { v -> vm.update { album = v } },
                    label = { Text("Album (empty = book title)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Every file is tagged with its chapter title, track number, album, artist and photo.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Speed: ${vm.parallel} parallel requests", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = vm.parallel.toFloat(),
                    onValueChange = { x -> vm.update { parallel = x.roundToInt().coerceIn(1, 8) } },
                    valueRange = 1f..8f,
                    steps = 6
                )
                Text(
                    "Several pieces of a chapter are synthesised at once. If you see many failures or HTTP 429, lower it.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (!unrestricted) {
                    OutlinedButton(onClick = {
                        try {
                            batteryLauncher.launch(
                                Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:" + ctx.packageName)
                                )
                            )
                        } catch (e: Exception) {
                            batteryLauncher.launch(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }) { Text("Allow background running (battery)") }
                    Text(
                        "Recommended: stops Android from slowing generation when you leave the app.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !running && vm.book != null,
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                                != PackageManager.PERMISSION_GRANTED
                            ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else vm.startExport(ctx)
                        }
                    ) { Text(if (running) "Generating…" else "Generate all selected") }
                    if (running) {
                        OutlinedButton(onClick = {
                            ctx.startService(Intent(ctx, TtsService::class.java).setAction(TtsService.ACTION_STOP))
                        }) { Text("Stop") }
                    }
                }
                val retryable = items.count { it.status == St.FAILED || it.status == St.CANCELLED }
                if (!running && retryable > 0) {
                    Button(onClick = { vm.retryFailed(ctx) }) { Text("Retry failed / interrupted ($retryable)") }
                    Text(
                        "Pieces that already finished are kept, so only the missing parts are generated again.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (!running && cacheBytes > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Saved partial pieces: ${"%.1f".format(cacheBytes / 1048576.0)} MB",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { ChunkCache.clear(ctx); cacheBytes = 0L }) { Text("Clear") }
                    }
                }
                if (items.isNotEmpty()) HorizontalDivider()
            }
        }
        items(items.size) { i ->
            val job = items[i]
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(job.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(job.fileName, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        when (job.status) {
                            St.QUEUED -> "queued"
                            St.RUNNING -> "${(job.progress * 100).toInt()}%"
                            St.DONE -> "done ✓"
                            St.FAILED -> "failed"
                            St.CANCELLED -> "cancelled"
                        },
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                if (job.status == St.RUNNING) {
                    LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
                }
                if (job.note.isNotBlank() &&
                    (job.status == St.FAILED || job.status == St.CANCELLED || job.status == St.RUNNING)
                ) {
                    Text(
                        job.note, style = MaterialTheme.typography.bodySmall,
                        color = if (job.status == St.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}
