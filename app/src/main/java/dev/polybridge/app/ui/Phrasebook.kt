package dev.polybridge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext

private data class Expression(val id: String, val category: String, val zh: String, val fr: String)

@Composable internal fun Phrasebook(play: (String) -> Unit) {
    val context = LocalContext.current
    val expressions = remember { context.assets.open("phrasebook/catalog.tsv").bufferedReader().useLines { rows ->
        rows.filter { it.isNotBlank() }.map { val (id, category, zh, fr) = it.split('\t'); Expression(id, category, zh, fr) }.toList()
    } }
    var category by rememberSaveable { mutableStateOf("见面") }
    Column(Modifier.fillMaxSize()) {
        PageHeading("CARNET DE POCHE", "常用表达", "离线中法词本 · 点语言旁的喇叭即可朗读")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            expressions.map { it.category }.distinct().forEach { group ->
                FilterChip(category == group, { category = group }, { Text(group) })
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(expressions.filter { it.category == category }, key = { it.fr }) { line ->
                Column(Modifier.fillMaxWidth().background(CardWhite, RoundedCornerShape(18.dp)).padding(horizontal = 15.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(line.zh, Modifier.weight(1f), color = Forest, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge)
                        ToolButton(Mark.SPEAK, "朗读中文 · ${line.zh}", { play("${line.id}-zh") }, Forest)
                    }
                    HorizontalDivider(color = Line)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(line.fr, Modifier.weight(1f), color = Clay, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic,
                            style = MaterialTheme.typography.titleMedium)
                        ToolButton(Mark.SPEAK, "Écouter · ${line.fr}", { play("${line.id}-fr") }, Clay)
                    }
                }
            }
        }
    }
}
