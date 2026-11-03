package dev.polybridge.app

import android.content.Intent
import android.net.Uri
import android.media.MediaFormat
import android.text.InputType
import android.widget.EditText
import android.view.inputmethod.EditorInfo
import dev.polybridge.app.audio.TranslatedAudioShare
import dev.polybridge.app.audio.AudioFileDecoder
import dev.polybridge.app.ime.*
import kotlinx.coroutines.runBlocking
import org.robolectric.shadows.ShadowMediaExtractor
import org.robolectric.shadows.util.DataSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceToolsApiTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test fun replacementChecksThatTheUserIsStillEditingTheSameSelection() {
        val editor = EditText(app)
        editor.setText("Bonjour Marie")
        editor.setSelection(8, 13)
        val connection = requireNotNull(editor.onCreateInputConnection(EditorInfo()))
        assertEquals(InsertResult.SELECTION_CHANGED, insertCandidate(connection, "Paul", "François"))
        assertEquals("Bonjour Marie", editor.text.toString())
        assertEquals(InsertResult.INSERTED, insertCandidate(connection, "Marie", "François"))
        assertEquals("Bonjour François", editor.text.toString())
    }

    @Test fun passwordFieldsDoNotOfferTranslation() {
        assertTrue(isPrivateEditor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertTrue(isPrivateEditor(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertTrue(isPrivateEditor(InputType.TYPE_NULL))
        assertFalse(isPrivateEditor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
    }

    @Test fun grantsReadAccessOnlyToTheSharedAudio() {
        val voice = TranslatedAudioShare(app)
        val uri = Uri.parse("content://dev.polybridge.app.speechfiles/speech/voice-100.wav")
        val intent = voice.outgoing(uri)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        assertEquals("audio/wav", intent.type)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
    }

    @Test fun selectedPcmTrackImportsAsCorrectlyTimedMonoSpeech() {
        val uri = Uri.parse("content://fixture/selected")
        val data = ByteBuffer.allocate(1600 * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(1600) { data.putShort(1000); data.putShort(3000) }
        val format = MediaFormat.createAudioFormat("audio/raw", 16000, 2)
        ShadowMediaExtractor.addTrack(DataSource.toDataSource(app, uri, null), format, data.array())
        val wave = runBlocking { AudioFileDecoder(app).read(uri) }
        assertEquals(44 + 3200, wave.size)
        assertEquals(2000, ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN).getShort(44).toInt())
    }
}
