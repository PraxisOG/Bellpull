package org.praxisog.hermes

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationEditorTest {
    @Test fun passwordAndAbsentEditorsDisableDictation() {
        listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD).forEach { kind ->
            assertFalse(canDictate(InputType.TYPE_CLASS_TEXT or kind or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        }
        assertFalse(canDictate(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertFalse(canDictate(InputType.TYPE_NULL))
        assertTrue(canDictate(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertTrue(canDictate(InputType.TYPE_CLASS_NUMBER))
    }
}
