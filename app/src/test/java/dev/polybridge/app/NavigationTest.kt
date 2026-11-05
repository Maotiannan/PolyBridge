package dev.polybridge.app

import dev.polybridge.app.ui.Page
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import org.junit.Assert.*
import org.junit.Test

class NavigationTest {
    @Test fun oldSerializedPhrasebookStateRestoresToConversation() {
        val bytes = ByteArrayOutputStream().apply {
            ObjectOutputStream(this).use { it.writeObject(Page.BOOK) }
        }.toByteArray()
        val restored = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as Page }
        assertEquals(Page.TALK, restored.visibleDestination)
    }

    @Test fun navigationRetainsCorePagesAndDoesNotExposeTheRetiredDestination() {
        assertEquals(listOf(Page.TALK, Page.HISTORY, Page.TOOLS), Page.mainDestinations)
        for (page in Page.entries.filterNot { it == Page.BOOK }) assertEquals(page, page.visibleDestination)
    }
}
