package dev.polybridge.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.polybridge.qwen.*

/** Edits only the dialog draft. Nothing reaches a running session until the next start. */
@Composable internal fun ModelSettings(selection: ModelSelection, changed: (ModelSelection) -> Unit) {
    var expanded by remember { mutableStateOf<ModelFeature?>(null) }
    Kicker("整套方案 · ${selection.label}")
    Text("一键调整四种语音功能，配套文字、识别和朗读保持 Flash。选择整套会清除下面的单项覆盖。", color = Muted,
        style = MaterialTheme.typography.bodySmall)
    ModelPreset.entries.forEach { preset ->
        Column(Modifier.testTag("preset-${preset.name}")) {
            ChoiceRow(preset.label, selection.preset == preset, { changed(selection.selectPreset(preset)) })
            Text(preset.description, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
    HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 8.dp))
    Kicker("按功能调整")
    ModelFeature.entries.forEach { feature ->
        TextButton(onClick = { expanded = if (expanded == feature) null else feature },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("feature-${feature.name}")) {
            Column(Modifier.fillMaxWidth()) {
                Text(feature.label)
                Text((if (feature in selection.overrides) "单独设置 · " else "跟随整套 · ") + ModelCatalog.label(selection.model(feature)),
                    color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (expanded == feature) {
            Text(feature.help, color = Muted, style = MaterialTheme.typography.bodySmall)
            ChoiceRow("跟随整套", feature !in selection.overrides, { changed(selection.override(feature, null)) })
            ModelCatalog.choices(feature).forEach { entry ->
                Column(Modifier.testTag("model-${feature.name}-${entry.id}")) {
                    ChoiceRow(entry.label, selection.overrides[feature] == entry.id, { changed(selection.override(feature, entry.id)) })
                    if (entry.note.isNotBlank()) Text(entry.note, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    Text("保存后从下一次开始生效；正在进行的录音、监听和播放保持原方案。模型失败会明确报错，不悄悄换模型。费用以百炼账单为准。",
        color = Muted, style = MaterialTheme.typography.bodySmall)
}

@Composable internal fun ModelStatus(selected: String, running: Boolean = false, requested: String? = null, actual: String? = null) {
    val current = requested?.takeIf { running } ?: selected
    Text(buildString {
        append(if (running) "本次 · " else "下次 · "); append(ModelCatalog.label(current))
        if (running) append(actual?.let { " · 服务返回 ${ModelCatalog.label(it)}" } ?: " · 服务未报告实际型号")
        if (running && selected != current) append("\n待生效 · ${ModelCatalog.label(selected)}")
    }, color = Muted, style = MaterialTheme.typography.labelSmall)
}
