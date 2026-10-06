package eu.studio742.imago.core.model

import org.junit.Assert.*
import org.junit.Test

class AssetReferenceTest {
    @Test fun sameRemoteIdInDifferentLibrariesNeverCollides() {
        assertNotEquals(AssetReference("server-a", "42").encode(), AssetReference("server-b", "42").encode())
        assertNotEquals(AssetReference(DEVICE_LIBRARY_ID, "42").encode(), AssetReference("server-a", "42").encode())
    }
    @Test fun contentUrisAndUnicodeRoundTrip() {
        val value = AssetReference("library:ç", "content://media/external_primary/images/media/42")
        assertEquals(value, AssetReference.parse(value.encode()))
        assertEquals(value.encode(), AssetReference.qualify("another", value.encode()))
    }
    @Test(expected = IllegalArgumentException::class) fun unqualifiedIdsCannotResolveAgainstCurrentSelection() {
        AssetReference.parse("42")
    }
}
