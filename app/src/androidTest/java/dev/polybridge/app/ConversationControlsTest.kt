package dev.polybridge.app

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Rect
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.polybridge.core.*
import dev.polybridge.app.audio.VoicePcm
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationControlsTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    @Before fun exposeControlIds() {
        val service = inst.uiAutomation.serviceInfo
        service.flags = service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        inst.uiAutomation.serviceInfo = service
    }

    private fun nodes(root: AccessibilityNodeInfo? = inst.uiAutomation.rootInActiveWindow): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val queue = ArrayDeque(listOf(root))
        return buildList {
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.packageName == "dev.polybridge.app" && node.isVisibleToUser) add(node)
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
            }
        }
    }

    private fun waitUntil(timeout: Long = 6000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < end) { "等待对话操作超时" }
            SystemClock.sleep(60)
        }
    }

    private fun labelled(label: String, id: String? = null): AccessibilityNodeInfo? {
        val all = nodes()
        val options = if (id == null) all else all.filter { it.viewIdResourceName == "turn-$id" }.flatMap { nodes(it) }
        return options.firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }
    }
    private fun click(label: String, id: String? = null) {
        // Use the touch path a person uses. Compose can expose the label as a separate text node.
        val bounds = Rect()
        var since = 0L
        waitUntil {
            val next = Rect()
            labelled(label, id)?.getBoundsInScreen(next)
            val now = SystemClock.uptimeMillis()
            if (next != bounds) { bounds.set(next); since = now }
            !bounds.isEmpty && now - since >= 300
        }
        tap(bounds)
    }

    private fun tap(bounds: Rect) {
        val command = "input -d 0 tap ${bounds.centerX()} ${bounds.centerY()}"
        android.os.ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    private fun type(index: Int, value: String) {
        val field = nodes().filter { it.isEditable }.getOrNull(index) ?: error("缺少第 ${index + 1} 个校对输入框")
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
        waitUntil { nodes().any { it.isEditable && it.text.toString().contains(value) } }
    }

    private fun speechLevel(wave: ByteArray): Double {
        val pcm = requireNotNull(VoicePcm.wave(wave))
        val meter = SignalMeter()
        val chunk = ShortArray(512)
        val end = pcm.offset + pcm.length
        var pos = pcm.offset
        while (pos < end) {
            val n = minOf(chunk.size, (end - pos) / 2)
            for (i in 0 until n) {
                chunk[i] = ((pcm.bytes[pos].toInt() and 255) or (pcm.bytes[pos + 1].toInt() shl 8)).toShort()
                pos += 2
            }
            meter.add(chunk, n)
        }
        return requireNotNull(meter.snapshot().rmsDbfs).also { assertTrue("译音振幅太低 $it", it > -50.0) }
    }

    @Test fun editBothLanguagesAndClearAllFromTheRealArchive() {
        // Clearing the archive must never run on an owner's handset.
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish") && InstrumentationRegistry.getArguments().getString("testArchive") == "true")
        val app = openProjectLauncher().application as PolyBridgeApp
        app.store.changeChat {
            (1..22).fold(Conversation()) { chat, i ->
                chat.typed("qa-$i", Side.CHINESE, "第${i}站").translated("qa-$i", "第${i}站", "Station $i.")
            }
        }
        click("历史")
        waitUntil { labelled("Station 22.") != null }
        val visible = nodes().mapNotNull { it.text?.toString() }.filter { it.matches(Regex("Station [0-9]+\\.")) }.distinct().size
        inst.sendStatus(0, Bundle().apply { putString("archiveVisible", "$visible widthsDp=" + app.resources.configuration.screenWidthDp) })
        click("纠错", "qa-22")
        waitUntil { nodes().count { it.isEditable } == 2 }
        type(0, "下一站")
        type(1, "Le prochain arrêt.")
        fun capture(name: String) {
            if (InstrumentationRegistry.getArguments().getString("captureQaScreens") != "true") return
            inst.uiAutomation.waitForIdle(180, 2000)
            val shot = inst.uiAutomation.takeScreenshot()
            app.cacheDir.resolve(name).outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            shot.recycle()
        }
        capture("qa-edit.png")
        if (InstrumentationRegistry.getArguments().getString("dialogIme") == "true") {
            val field = Rect()
            nodes().last { it.isEditable }.getBoundsInScreen(field)
            tap(field)
            val keyboard = Rect()
            val save = Rect()
            waitUntil {
                inst.uiAutomation.windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                    ?.getBoundsInScreen(keyboard)
                labelled("保存修改")?.getBoundsInScreen(save)
                !keyboard.isEmpty && !save.isEmpty && save.bottom <= keyboard.top
            }
            capture("qa-edit-ime.png")
            inst.sendStatus(0, Bundle().apply { putString("dialogIme", "saveBottom=" + save.bottom + " keyboardTop=" + keyboard.top) })
        }
        click("保存修改")
        waitUntil { labelled("Le prochain arrêt.") != null }
        val saved = ProbeStore(app).chat.value.turns.single { it.id == "qa-22" }
        assertEquals("下一站", saved.sourceText)
        assertEquals("Le prochain arrêt.", saved.translatedText)
        click("清空历史")
        waitUntil { labelled("还没有对话") != null }
        assertTrue(ProbeStore(app).chat.value.turns.isEmpty())
        click("对话")
        assertTrue(app.store.chat.value.currentTurns.isEmpty())
    }

    @Test fun captureArchiveWithBilingualPhrasesOnIsolatedDevice() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish") && InstrumentationRegistry.getArguments().getString("archiveScreen") == "true")
        val app = openProjectLauncher().application as PolyBridgeApp
        val phrases = app.assets.open("phrasebook/catalog.tsv").bufferedReader().use { it.readLines() }.map { it.split('\t') }
        app.store.changeChat {
            phrases.foldIndexed(Conversation()) { i, chat, fields ->
                val side = Side.entries[i % 2]
                val source = fields[if (i % 2 == 0) 2 else 3]
                chat.typed("phrase-$i", side, source).translated("phrase-$i", source, fields[if (i % 2 == 0) 3 else 2])
            }
        }
        click("历史")
        waitUntil { labelled("对话历史") != null && labelled("纠错", "phrase-${phrases.lastIndex}") != null }
        inst.uiAutomation.waitForIdle(300, 4000)
        SystemClock.sleep(400)
        val bitmap = requireNotNull(inst.uiAutomation.takeScreenshot())
        try {
            java.io.File(app.cacheDir, "qa-phrase-archive.png").outputStream().use { out ->
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out))
            }
        } finally { bitmap.recycle() }
    }

    @Test fun holdingTheOtherLanguageStopsReplayAndOwnsTheMicrophoneUntilRelease() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("testHoldBargeIn") == "true")
        val app = openProjectLauncher().application as PolyBridgeApp
        assumeTrue(app.store.active.value == null && app.store.chat.value.turns.size < 98 && app.settings.state.value.configured)
        val id = java.util.UUID.randomUUID().toString()
        val target = "Je voudrais acheter deux billets."
        val bounds = Rect()
        var pressed = false
        var down = 0L
        fun touch(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try { assertTrue(inst.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        try {
            app.store.changeChat { it.typed(id, Side.CHINESE, "两张票").translated(id, "两张票", target) }
            val turn = app.store.chat.value.turns.single { it.id == id }
            val wave = app.assets.open("phrasebook/tickets-fr.wav").use { it.readBytes() }
            val pcm = wave.copyOfRange(44, wave.size)
            app.clips.put(app.clips.key(turn, app.settings.state.value.cloud), waveFromPcm(24000, pcm + pcm + pcm))
            click("重播", id)
            waitUntil { app.settings.lastRoutes.value["fr-FR"]?.let { it.manual && it.gain > 0 && it.positionMs > 100 } == true }
            requireNotNull(labelled("Français, maintenir pour parler")).getBoundsInScreen(bounds)
            down = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN); pressed = true
            waitUntil { app.store.active.value?.let { it.side == Side.FRENCH && it.sampling.value } == true }
            val held = requireNotNull(app.store.active.value)
            waitUntil { labelled("重播", id) != null }
            val routed = app.settings.lastRoutes.value["fr-FR"]
            labelled("中文，按住说话")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            SystemClock.sleep(350)
            assertEquals(held.id, app.store.active.value?.id)
            assertEquals(routed, app.settings.lastRoutes.value["fr-FR"])
            touch(MotionEvent.ACTION_CANCEL); pressed = false
            waitUntil { app.store.active.value == null }
            assertTrue(app.store.chat.value.turns.none { it.id == held.id })
        } finally {
            if (pressed) { app.store.active.value?.end?.compareAndSet(null, End.CANCELLED); touch(MotionEvent.ACTION_CANCEL) }
            app.store.changeChat { chat -> chat.copy(turns = chat.turns.filterNot { it.id == id }) }
        }
    }

    @Test fun replayButtonDeliversBothLanguagesAndASecondTapUsesSavedAudio() {
        val args = InstrumentationRegistry.getArguments()
        val cloud = args.getString("liveReplay") == "true"
        val fixture = Build.HARDWARE in setOf("ranchu", "goldfish") && args.getString("testArchive") == "true"
        assumeTrue(cloud || fixture)
        val app = openProjectLauncher().application as PolyBridgeApp
        assumeTrue(app.store.chat.value.turns.size < 98 && app.store.active.value == null)
        val settings = app.settings
        val created = mutableSetOf<String>()
        try {
            for ((index, side) in Side.entries.withIndex()) {
                val id = java.util.UUID.randomUUID().toString()
                created += id
                val source = if (side == Side.CHINESE) "请问火车站在哪里？" else "Où est la gare ?"
                val target = if (side == Side.CHINESE) "Où est la gare ?" else "请问，火车站在哪里？"
                app.store.changeChat { it.typed(id, side, source).translated(id, source, target) }
                val turn = app.store.chat.value.turns.first { it.id == id }
                val key = app.clips.key(turn, settings.state.value.cloud)
                if (fixture) app.clips.put(key, app.assets.open("phrasebook/station-${side.target.substringBefore('-')}.wav").use { it.readBytes() })
                else assertNull(app.clips[key])
                if (index == 0) { click("历史"); waitUntil { labelled("对话历史") != null } }
                waitUntil { labelled(target) != null }
                repeat(2) {
                    val earlier = settings.lastRoutes.value[side.target]?.playbackId
                    val cached = app.clips[key]
                    click("重播", id)
                    var actual: TranslationSettings.PlaybackObservation? = null
                    var nextLog = SystemClock.elapsedRealtime() + 1000
                    fun diagnostic(): String {
                        val feedback = nodes().mapNotNull { it.text?.toString() }.filter { text ->
                            listOf("准备声音", "播放中", "静音", "无法朗读", "没有声音", "指定耳机", "播放失败").any { text.contains(it) }
                        }.take(3)
                        val ports = dev.polybridge.app.audio.availablePorts(app).filter { it.isSink }.map { it.type to it.id }
                        return "route=${settings.lastRoutes.value[side.target]} bytes=${app.clips[key]?.size} state=$feedback ports=$ports"
                    }
                    try { waitUntil(40000) {
                        val route = settings.lastRoutes.value[side.target]
                        if (route?.manual == true && route.playbackId != earlier && route.positionMs >= 150 && route.gain > 0) actual = route
                        if (actual == null && SystemClock.elapsedRealtime() >= nextLog) {
                            android.util.Log.i("PolyBridgePlaybackQA", diagnostic())
                            nextLog = SystemClock.elapsedRealtime() + 3000
                        }
                        actual != null
                    } } catch (error: IllegalStateException) {
                        throw AssertionError(diagnostic(), error)
                    }
                    val route = requireNotNull(actual)
                    if (args.getString("requireHeadset") == "true") assertTrue(dev.polybridge.app.audio.headphoneType(route.type))
                    val level = speechLevel(requireNotNull(app.clips[key]))
                    inst.sendStatus(0, Bundle().apply { putString("manualReplay", "${side.target} round=$it type=${route.type} gain=${route.gain} positionMs=${route.positionMs} rmsDbfs=$level") })
                    if (cached != null) assertSame(cached, app.clips[key])
                    waitUntil(20000) { labelled("重播", id) != null }
                }
                if (cloud && index == 0) {
                    click("纠错", id)
                    waitUntil { nodes().count { it.isEditable } == 2 }
                    type(0, "下一班火车什么时候到？")
                    type(1, "Quand arrive le prochain train ?")
                    click("保存修改")
                    val corrected = "Quand arrive le prochain train ?"
                    waitUntil { labelled(corrected, id) != null }
                    val revision = ProbeStore(app).chat.value.turns.single { it.id == id }
                    val newKey = app.clips.key(revision, settings.state.value.cloud)
                    assertNull(app.clips[newKey])
                    val before = settings.lastRoutes.value[side.target]?.playbackId
                    click("重播", id)
                    waitUntil(60000) {
                        val route = settings.lastRoutes.value[side.target]
                        route?.manual == true && route.playbackId != before && route.positionMs > 150 && route.gain > 0 && app.clips[newKey] != null
                    }
                    assertFalse(requireNotNull(app.clips[newKey]).contentEquals(requireNotNull(app.clips[key])))
                    speechLevel(requireNotNull(app.clips[newKey]))
                    waitUntil(20000) { labelled("重播", id) != null }
                    inst.sendStatus(0, Bundle().apply { putString("correctedReplay", "both fields persisted; fresh Qwen audio advanced >150ms") })
                }
            }
        } finally { app.store.changeChat { chat -> chat.copy(turns = chat.turns.filterNot { it.id in created }) } }
    }
}
