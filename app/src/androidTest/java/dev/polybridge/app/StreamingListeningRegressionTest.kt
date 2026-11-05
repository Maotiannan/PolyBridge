package dev.polybridge.app

import android.media.projection.MediaProjection
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.polybridge.app.audio.*
import dev.polybridge.core.PlaybackFloor
import dev.polybridge.app.ui.ScreenCaptions
import dev.polybridge.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class StreamingListeningRegressionTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = inst.targetContext.applicationContext as PolyBridgeApp

    // OriginOS can suspend a background instrumentation worker even with USB screen-on.
    // A visible project activity keeps fixed-audio checks in the same foreground state as listening.
    @Before fun bringAudioChecksToForeground() { openProjectLauncher() }

    private fun wave(bytes: ByteArray): ShortArray {
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val rate = input.getInt(24)
        return ShortArray(((bytes.size - 44L) / 2 * 16000 / rate).toInt()) { i ->
            input.getShort(44 + 2 * (i.toLong() * rate / 16000).toInt())
        }
    }

    private fun runSession(kind: Kind, link: ((LiveUpdate) -> Unit) -> LiveSpeechSession,
        feed: (MicFrames) -> Unit, beforeStop: ((LiveDesk, ProbeRequest) -> Unit)? = null, inspect: (ProbeStore, LiveDesk) -> Unit) {
        val name = "qa-stream-" + UUID.randomUUID()
        val store = ProbeStore(app, name)
        lateinit var desk: LiveDesk
        desk = LiveDesk(app, store, app.settings, VoiceClips(), connect = link, captionConnection = link, microphone = { frame ->
            object : AudioInputSource {
                override fun measure(request: ProbeRequest, projection: MediaProjection?, onStarted: () -> Unit): ProbeEntry {
                    onStarted(); feed(frame); beforeStop?.invoke(desk, request); request.end.set(End.RELEASED)
                    return request.empty(Verdict.SIGNAL)
                }
            }
        })
        try {
            val request = requireNotNull(store.begin(kind, PlaybackTarget.SELF, null, null, null, "qa.player"))
            val report = desk.record(request)
            assertEquals(report.errorCode, Verdict.SIGNAL, report.verdict)
            store.finish(report)
            inspect(ProbeStore(app, name), desk)
        } finally { app.deleteSharedPreferences(name) }
    }

    private open class Sink : LiveSpeechSession {
        override fun connect() = Unit
        override fun send(samples: ShortArray, length: Int, rate: Int) = Unit
        override fun finish() = Unit
        override fun close() = Unit
    }

    @Test fun rainAloneStaysSilentAndBothLanguagesSurviveAPauseInRain() {
        val rains = listOf("1-21189-A-10", "2-73027-A-10").map {
            inst.context.assets.open("rain/$it.wav").use { file -> wave(file.readBytes()) }
        }
        val voices = listOf("tickets-fr", "station-zh").map {
            app.assets.open("phrasebook/$it.wav").use { file -> wave(file.readBytes()) }
        }
        for ((index, kind) in listOf(Kind.LISTEN, Kind.CAPTIONS).withIndex()) {
            val rate = if (index == 0) 16000 else 48000
            val rain = rains[index]
            val lead = rain.size * 2
            val middle = 12800
            val starts = listOf(lead, lead + voices[0].size + middle)
            val size = starts[1] + voices[1].size
            val source = ShortArray(size) { i ->
                val clip = if (i < starts[1]) 0 else 1
                val word = voices[clip].getOrNull(i - starts[clip])?.toInt() ?: 0
                (rain[i % rain.size] * (if (i < rain.size) .6 else .15) + word * .45).toInt().coerceIn(-32768, 32767).toShort()
            }
            val heard = ArrayList<Short>(size)
            val sink = object : Sink() {
                override fun send(samples: ShortArray, length: Int, rate: Int) {
                    for (i in 0 until length step (rate / 16000)) heard.add(samples[i])
                }
            }
            runSession(kind, { sink }, { push ->
                var offset = 0
                val scale = rate / 16000
                while (offset < size * scale) {
                    val n = minOf(if (offset % 2 == 0) 319 else 962, size * scale - offset)
                    val block = ShortArray(n) { source[(offset + it) / scale] }
                    push(block, n, rate); offset += n
                }
            }) { _, _ ->
                val rainPassed = heard.take(lead - 4000).count { it != 0.toShort() }
                assertEquals("雨声触发了识别输入 ($kind)", 0, rainPassed)
                assertEquals("不能压缩分段时间或丢掉结尾", size, heard.size)
                val retained = voices.mapIndexed { v, pcm ->
                    val spoken = pcm.indices.filter { abs(pcm[it].toInt()) > 900 }
                    val first = spoken.first()
                    assertTrue("第一个音节丢失", spoken.takeWhile { it < first + 2400 }.count { heard[starts[v] + it] != 0.toShort() } >= 100)
                    spoken.count { heard[starts[v] + it] != 0.toShort() }.toDouble() / spoken.size
                }
                assertTrue("人声保留比例 $retained", retained.all { it >= .88 })
                inst.sendStatus(0, Bundle().apply { putString("rainAndSpeech", "$kind rate=$rate rainSamplesPassed=$rainPassed voicedSamplesRetained=$retained") })
            }
        }
    }

    @Test fun aMinuteOfFrenchPlaybackKeepsEveryPhraseOnBothSidesOfEachBreath() {
        val clip = inst.context.assets.open("rain/breathing-fr.wav").use { wave(it.readBytes()) }
        val gaps = List(12) { if (it % 2 == 0) 4000 else 35200 }
        val captured = ShortArray(clip.size * gaps.size + gaps.sum())
        var written = 0
        var connections = 0
        val sink = object : Sink() {
            override fun connect() { connections++ }
            override fun send(samples: ShortArray, length: Int, rate: Int) {
                samples.copyInto(captured, written, 0, length)
                written += length
            }
        }
        runSession(Kind.CAPTIONS, { sink }, { send ->
            for (gap in gaps) {
                send(clip, clip.size, 16000)
                send(ShortArray(gap), gap, 16000)
            }
        }) { _, _ ->
            assertEquals(1, connections)
            assertEquals(captured.size, written)
            var start = 0
            val retention = mutableListOf<Double>()
            for (gap in gaps) {
                // The two recorded French phrases lie on either side of the breath at 2.5 s.
                for (phrase in listOf(0 until 40000, 40000 until clip.size)) {
                    val strong = phrase.filter { abs(clip[it].toInt()) > 1200 }
                    assertTrue(strong.size > 1000)
                    retention += strong.count { captured[start + it] == clip[it] }.toDouble() / strong.size
                }
                start += clip.size + gap
            }
            assertTrue("连续播放有语音丢失 $retention", retention.all { it >= .96 })
            inst.sendStatus(0, Bundle().apply { putString("longPlayback",
                "seconds=${written / 16000.0} phrases=${retention.size} minimumStrongSampleRetention=${retention.min()} connections=$connections") })
        }
    }

    @Test fun aBreathAndLateFinalsRetainBothSidesInSpokenOrderAfterReopen() {
        lateinit var publish: (LiveUpdate) -> Unit
        fun draft(id: String, at: Long) = LiveUpdate.Caption(id, Side.FRENCH, "phrase $id", "译文$id", at)
        fun done(id: String, at: Long) = LiveUpdate.Finished(id, Side.FRENCH, at, "phrase $id", "译文$id", byteArrayOf())
        runSession(Kind.CAPTIONS, { events ->
            publish = events
            object : Sink() { override fun finish() { publish(done("C", 5100)) } }
        }, {
            publish(draft("A", 100)); publish(draft("B", 2400))
            publish(done("B", 2400)); publish(done("A", 100))
            publish(done("B", 2400)); publish(draft("C", 5100))
        }) { store, _ ->
            assertEquals(listOf("phrase A", "phrase B", "phrase C"), store.chat.value.turns.map { it.sourceText })
            assertEquals(listOf("译文A", "译文B", "译文C"), store.chat.value.turns.map { it.translatedText })
        }
    }

    @Test fun stoppingBeforeSpeechIsConfirmedDoesNotReleaseTheLookback() {
        val phrase = app.assets.open("phrasebook/tickets-fr.wav").use { wave(it.readBytes()) }
        val blockSize = 512
        var firstOpen = 0
        var forwarded = false
        val sink = object : Sink() {
            override fun send(samples: ShortArray, length: Int, rate: Int) {
                if ((0 until length).any { samples[it] != 0.toShort() }) forwarded = true
            }
        }
        SpeechGate(app, sink).use { gate ->
            for (end in blockSize..phrase.size step blockSize) {
                gate.send(phrase.copyOfRange(end - blockSize, end), blockSize, 16000)
                if (forwarded) { firstOpen = end; break }
            }
        }
        assertTrue("样本没有出现人声起点", firstOpen >= blockSize * 2)
        forwarded = false
        SpeechGate(app, sink).use { gate ->
            gate.send(phrase, firstOpen - blockSize, 16000)
            assertFalse(forwarded)
            gate.finish()
            assertFalse("停止时释放了尚未确认为人声的输入", forwarded)
        }
    }

    @Test fun quietFrenchAboveHumKeepsTheOpeningQuestion() {
        openProjectLauncher()
        val voice = app.assets.open("phrasebook/train-fr.wav").use { wave(it.readBytes()) }
        val lead = 32000
        val size = lead + voice.size + 16000
        for (silentPrelude in listOf(false, true)) {
            val heard = ArrayList<Short>(size)
            val sink = object : Sink() {
                override fun send(samples: ShortArray, length: Int, rate: Int) {
                    for (i in 0 until length) heard.add(samples[i])
                }
            }
            SpeechGate(app, sink).use { gate ->
                for (start in 0 until size step 320) {
                    val samples = ShortArray(minOf(320, size - start)) { i ->
                        val at = start + i
                        if (silentPrelude && at < lead) 0 else ((voice.getOrNull(at - lead)?.toInt() ?: 0) * .14 +
                            300 * kotlin.math.sin(at * 2 * Math.PI * 140 / 16000)).toInt().toShort()
                    }
                    gate.send(samples, samples.size, 16000)
                }
                gate.finish()
            }
            assertEquals(size, heard.size)
            val opening = (0 until minOf(voice.size, 16000)).filter { abs(voice[it].toInt()) > 1200 }
            val kept = opening.count { heard[lead + it] != 0.toShort() }.toDouble() / opening.size
            inst.sendStatus(0, Bundle().apply { putString("quietQuestion", "silentPrelude=$silentPrelude openingRetention=$kept") })
            assertEquals(0, heard.take(lead - 4000).count { it != 0.toShort() })
            assertTrue("轻声问题开头被本机人声检测裁掉：$kept", kept >= .9)
        }
    }

    @Test fun rainDuringPlaybackCannotBypassSpeechDetectionWhenItTriggersTheFloor() {
        val reference = app.assets.open("phrasebook/tickets-fr.wav").use { it.readBytes().drop(44).toByteArray() }
        for (name in listOf("1-21189-A-10", "2-73027-A-10")) {
            val rain = inst.context.assets.open("rain/$name.wav").use { wave(it.readBytes()) }
            var passed = 0
            var sent = 0
            var interruptions = 0
            var ducks = 0
            var muted = -1L
            var speaking = true
            val echo = PlaybackEcho(212.0).apply { start(reference); position(0, 0) }
            val sink = object : Sink() {
                override fun send(samples: ShortArray, length: Int, rate: Int) {
                    sent += length
                    passed += (0 until length).count { samples[it] != 0.toShort() }
                }
            }
            SpeechGate(app, sink, enhanced = true).use { gate ->
                val floor = PlaybackFloor(gate::recheckOpening, gate::voiceBeforeDuck, gate::resetVoiceBeforeDuck)
                for (offset in rain.indices step 320) {
                    val now = offset / 16 + 20L
                    if (speaking) echo.position((now - 20).toInt(), now - 20)
                    val frame = rain.copyOfRange(offset, minOf(offset + 320, rain.size))
                    floor.send(frame, frame.size, 16000, now, echo, speaking, false, 212.0, muted, gate,
                        duck = { if (it) ducks++; muted = if (it) now else -1L }, interrupt = {
                            interruptions++; speaking = false; echo.stop(now)
                        })
                }
                // Drain the pending floor history through its normal non-playing path.
                floor.send(ShortArray(320), 320, 16000, rain.size / 16 + 20L, echo, false, false,
                    212.0, muted, gate, duck = {}, interrupt = {})
                gate.finish()
            }
            inst.sendStatus(0, Bundle().apply { putString("rainDuringPlayback",
                "fixture=$name ducks=$ducks interruptions=$interruptions passed=$passed samples=$sent") })
            assertEquals(rain.size + 320, sent)
            assertEquals("疑似抢话让纯雨声绕过了人声检测：$name", 0, passed)
            assertEquals("纯雨声中止了播报：$name", 0, interruptions)
            assertEquals("纯雨声反复压低播报：$name", 0, ducks)
        }
    }

    @Test fun reconnectionPreservesDraftsAndDoesNotReuseAnOldSentence() {
        lateinit var publish: (LiveUpdate) -> Unit
        runSession(Kind.LISTEN, { publish = it; Sink() }, { send ->
            send(ShortArray(16000), 16000, 16000)
            publish(LiveUpdate.Caption("1", Side.FRENCH, "Avant", "之前", 100))
            publish(LiveUpdate.Caption("2", Side.FRENCH, "Bruit", "", 600))
            publish(LiveUpdate.Withdrawn("2", Side.FRENCH))
            publish(LiveUpdate.Reconnecting(1)); publish(LiveUpdate.Reconnected)
            publish(LiveUpdate.Finished("1", Side.FRENCH, 10, "Après", "之后", byteArrayOf()))
            publish(LiveUpdate.Caption("3", Side.CHINESE, "最后一段", "", 200))
        }) { store, _ ->
            val lines = store.chat.value.turns
            assertEquals(listOf("Avant", "Après", "最后一段"), lines.map { it.sourceText })
            assertEquals(listOf(TranslationIssue.NETWORK, null, TranslationIssue.INTERRUPTED), lines.map { it.issue })
            assertEquals(listOf("之前", "之后", ""), lines.map { it.translatedText })
        }
    }

    @Test(timeout = 180000) fun realQwenIgnoresRainAndTranslatesTheEntireBreathFixture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cloudRain") == "true")
        openProjectLauncher()
        val rounds = InstrumentationRegistry.getArguments().getString("cloudRainRepeats")?.toIntOrNull()?.coerceIn(1, 10) ?: 1
        fun phase(name: String) = inst.sendStatus(0, Bundle().apply { putString("cloudRainPhase", name) })
        phase("provider")
        val api = requireNotNull(app.settings.provider())
        phase("connect")
        val noiseOnly = AtomicBoolean(true)
        val noiseCaption = AtomicBoolean()
        fun play(asset: String, send: MicFrames) {
            val pcm = inst.context.assets.open("rain/$asset.wav").use { wave(it.readBytes()) }
            for (i in pcm.indices step 1600) {
                val block = pcm.copyOfRange(i, minOf(i + 1600, pcm.size))
                send(block, block.size, 16000)
                SystemClock.sleep(block.size * 1000L / 16000)
            }
        }
        runSession(Kind.LISTEN, { emit -> api.live { event ->
            if (noiseOnly.get() && (event is LiveUpdate.Caption || event is LiveUpdate.Finished)) noiseCaption.set(true)
            // Exercise the ordinary conversation model without playing its response into the room.
            emit(if (event is LiveUpdate.Finished) event.copy(pcm = byteArrayOf()) else event)
        } }, { send ->
            phase("rain")
            play("1-21189-A-10", send)
            play("2-73027-A-10", send)
            val quiet = ShortArray(1600)
            repeat(18) { send(quiet, quiet.size, 16000); SystemClock.sleep(100) }
            noiseOnly.set(false)
            repeat(rounds) { index ->
                phase("speech ${index + 1}/$rounds")
                play("breathing-fr", send)
                repeat(20) { send(quiet, quiet.size, 16000); SystemClock.sleep(100) }
            }
            phase("finish")
        }) { store, _ ->
            phase("verify")
            assertFalse("纯雨声触发了千问字幕", noiseCaption.get())
            val turns = store.chat.value.turns
            assertTrue(turns.isNotEmpty() && turns.all { it.side == Side.FRENCH && it.status == TurnStatus.READY })
            val source = turns.joinToString(" ") { it.sourceText }.lowercase()
            val target = turns.joinToString(" ") { it.translatedText }
            assertTrue("法语缺少换气前或换气后的内容", source.indexOf("billet") >= 0 && source.indexOf("gare") > source.indexOf("billet"))
            assertTrue("译文缺少换气前或换气后的内容", target.indexOf("票") >= 0 && target.indexOf("站") > target.indexOf("票"))
            val tickets = Regex("billets?").findAll(source).count()
            val stations = Regex("gare").findAll(source).count()
            assertTrue("长时间监听丢了前面的原文", tickets >= rounds && stations >= rounds)
            assertTrue("长时间监听丢了前面的译文", target.count { it == '票' } >= rounds && target.count { it == '站' } >= rounds)
            inst.sendStatus(0, Bundle().apply { putString("cloudRain",
                "rainCaptions=0 pairs=$rounds savedParagraphs=${turns.size} ticketMentions=$tickets stationMentions=$stations") })
        }
    }

    @Test fun theFloatingWindowAndReopenedCaptionPageBothShowPreviousSentences() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val ui = inst.uiAutomation
        fun shell(cmd: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(cmd))
            .bufferedReader().use { it.readText() }
        fun find(label: String, contains: Boolean = false): AccessibilityNodeInfo {
            val end = SystemClock.elapsedRealtime() + 5000
            while (SystemClock.elapsedRealtime() < end) {
                val queue = ArrayDeque(ui.windows.mapNotNull { it.root } + listOfNotNull(ui.rootInActiveWindow))
                while (queue.isNotEmpty()) {
                    val node = queue.removeFirst()
                    val value = node.text?.toString().orEmpty()
                    if (node.packageName == app.packageName && (node.contentDescription == label ||
                        (if (contains) value.contains(label) else value == label))) return node
                    for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
                }
                SystemClock.sleep(60)
            }
            error("找不到 $label")
        }
        fun click(label: String) {
            var node: AccessibilityNodeInfo? = find(label)
            while (node != null) {
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                node = node.parent
            }
            error("无法点击 $label")
        }
        fun scroller(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
            var parent = node.parent
            while (parent != null && !parent.isScrollable) parent = parent.parent
            return requireNotNull(parent) { "记录没有可滚动的父容器" }
        }
        fun waitForScroll(ready: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 4000
            while (!ready()) {
                check(SystemClock.elapsedRealtime() < until) { "滚动位置不符" }
                SystemClock.sleep(60)
            }
        }
        fun canScrollForward(node: AccessibilityNodeInfo) =
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
        val oldFlags = ui.serviceInfo.flags
        val oldOp = Regex("""SYSTEM_ALERT_WINDOW:\s*(allow|ignore|deny|default|foreground)""")
            .find(shell("appops get ${app.packageName} SYSTEM_ALERT_WINDOW"))?.groupValues?.get(1) ?: "default"
        val original = app.store.chat.value
        try {
            ui.serviceInfo = ui.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow")
            openProjectLauncher()
            lateinit var notify: (LiveUpdate) -> Unit
            fun sentence(i: Int) = LiveUpdate.Finished("line$i", Side.FRENCH, i * 1300L, "Phrase $i.", "第${i}句。", byteArrayOf())
            runSession(Kind.CAPTIONS, { notify = it; Sink() }, {
                for (i in 1..16) notify(sentence(i))
            }, beforeStop = { desk, req ->
                val overlay = ScreenCaptions(app, req, desk) {}
                try {
                    inst.runOnMainSync { overlay.show() }
                    find("第16句。"); click("查看字幕全文")
                    val all = find("Phrase 1.", contains = true).text.toString()
                    assertEquals((1..16).joinToString("\n\n") { "法语 → 中文\nPhrase $it.\n第${it}句。" }, all)
                    fun overlayScroll() = scroller(find("Phrase 1.", contains = true))
                    waitForScroll { !canScrollForward(overlayScroll()) }
                    assertTrue(overlayScroll().performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
                    waitForScroll { canScrollForward(overlayScroll()) }
                    notify(sentence(17))
                    find("第17句。", contains = true)
                    assertTrue("新字幕抢走了正在阅读的位置", canScrollForward(overlayScroll()))
                    click("收起字幕全文"); find("第17句。")
                } finally { inst.runOnMainSync { overlay.close() } }
            }) { store, _ -> app.store.changeChat { store.chat.value } }
            click("屏幕字幕")
            find("第17句。")
            fun visibleOriginals(list: AccessibilityNodeInfo): List<String> {
                val children = ArrayDeque(listOf(list))
                val visible = mutableListOf<String>()
                while (children.isNotEmpty()) {
                    val node = children.removeFirst()
                    node.text?.toString()?.takeIf { it.startsWith("Phrase ") }?.let { visible += it }
                    for (i in 0 until node.childCount) node.getChild(i)?.let(children::addLast)
                }
                return visible
            }
            val deadline = SystemClock.elapsedRealtime() + 8000
            var list = scroller(find("Phrase ", contains = true))
            var visible = visibleOriginals(list)
            while ("Phrase 1." !in visible && SystemClock.elapsedRealtime() < deadline) {
                // TalkBack exposes scrolling to older rows as scrolling backwards.
                list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                SystemClock.sleep(420)
                list = scroller(find("Phrase ", contains = true))
                visible = visibleOriginals(list)
            }
            assertTrue("没有翻到第一段，屏幕上仍是 $visible，操作 ${list.actionList.map { it.id }}", "Phrase 1." in visible)
            find("第1句。")
        } finally {
            app.store.changeChat { original }
            ui.serviceInfo = ui.serviceInfo.apply { flags = oldFlags }
            shell("appops set ${app.packageName} SYSTEM_ALERT_WINDOW $oldOp")
            openProjectLauncher()
        }
    }
}
