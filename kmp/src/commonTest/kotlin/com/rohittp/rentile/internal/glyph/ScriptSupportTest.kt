package com.rohittp.rentile.internal.glyph

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptSupportTest {
    @Test
    fun latinGreekCyrillicAndCjkAreSupported() {
        assertTrue(ScriptSupport.isSupported("Paris"))
        assertTrue(ScriptSupport.isSupported("Αθήνα"))
        assertTrue(ScriptSupport.isSupported("Москва"))
        assertTrue(ScriptSupport.isSupported("東京"))
        assertTrue(ScriptSupport.isSupported("서울"))
        assertTrue(ScriptSupport.isSupported("Saint-Jean-de-Luz (1)"))
    }

    @Test
    fun reorderingAndJoiningScriptsAreNotSupported() {
        assertFalse(ScriptSupport.isSupported("القاهرة"))
        assertFalse(ScriptSupport.isSupported("תל אביב"))
        assertFalse(ScriptSupport.isSupported("नई दिल्ली"))
        assertFalse(ScriptSupport.isSupported("กรุงเทพ"))
        assertFalse(ScriptSupport.isSupported("ភ្នំពេញ"))
        assertFalse(ScriptSupport.isSupported("ဝန်းသိုမြို့"))
    }

    @Test
    fun mixedTextIsUnsupportedIfAnyPartIsUnsupported() {
        assertFalse(ScriptSupport.isSupported("Cairo القاهرة"))
    }
}
