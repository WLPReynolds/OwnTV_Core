package tv.own.owntv.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistListsTest {
    @Test
    fun itemKeyMatchesTheCustomizationKeyShape() {
        assertEquals("7:abc", PlaylistLists.itemKey(7, "abc", "Name"))
        assertEquals("7:Name", PlaylistLists.itemKey(7, null, "Name"))
    }

    @Test
    fun repointOnlyTouchesKeysOfTheOldPlaylist() {
        assertEquals("9:News", PlaylistLists.repoint("7:News", 7, 9))
        assertEquals("70:News", PlaylistLists.repoint("70:News", 7, 9))
        assertEquals("custom:x", PlaylistLists.repoint("custom:x", 7, 9))
    }

    @Test
    fun recognisesItsOwnFileFormats() {
        assertTrue(PlaylistLists.looksLikeEdits("""{"format":"slightlygood-edits","version":1}"""))
        assertTrue(PlaylistLists.looksLikeEdits("""{"format":"slightlygood-lists"}"""))
        assertFalse(PlaylistLists.looksLikeEdits("""{"version":26,"profiles":[]}"""))
    }
}
