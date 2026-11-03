package dev.polybridge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable internal fun StudioDialog(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
    text: @Composable () -> Unit, confirmButton: @Composable () -> Unit, dismissButton: (@Composable () -> Unit)? = null) {
    Dialog(onDismissRequest, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))) {
            val wide = maxWidth >= 600.dp
            Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null,
                onClickLabel = "关闭面板", onClick = onDismissRequest))
            Surface(modifier = Modifier.align(if (wide) Alignment.Center else Alignment.BottomCenter)
                .padding(horizontal = if (wide) 24.dp else 8.dp, vertical = if (wide) 20.dp else 6.dp)
                .widthIn(max = 520.dp).fillMaxWidth().heightIn(max = maxHeight * .94f).semantics { paneTitle = "操作面板" },
                shape = RoundedCornerShape(26.dp), color = Paper, tonalElevation = 2.dp) {
                Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 13.dp, bottom = 12.dp)) {
                    Box(Modifier.align(Alignment.CenterHorizontally).width(32.dp).height(4.dp)
                        .background(Line, RoundedCornerShape(4.dp)))
                    Spacer(Modifier.height(13.dp))
                    CompositionLocalProvider(LocalContentColor provides Ink) {
                        ProvideTextStyle(MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold), title)
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())) { text() }
                    HorizontalDivider(Modifier.padding(top = 10.dp), color = Line)
                    Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically) {
                        dismissButton?.invoke(); Spacer(Modifier.width(8.dp)); confirmButton()
                    }
                }
            }
        }
    }
}

@Composable internal fun ChoiceRow(label: String, selected: Boolean, choose: () -> Unit, detail: String? = null) {
    Surface(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), color = if (selected) Mint else CardWhite,
        shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = choose)
            .heightIn(min = 50.dp).padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, color = Ink, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                if (detail != null) Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            if (selected) Glyph(Mark.CHECK, Forest, 19.dp)
        }
    }
}
