package com.watchwire.app.network

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlNormalizationTest {

    @Test
    fun `leaves ws and wss untouched`() {
        assertEquals("ws://192.168.1.6:8000", normalizeWsUrl("ws://192.168.1.6:8000"))
        assertEquals("wss://watchwire.example.com", normalizeWsUrl("wss://watchwire.example.com"))
    }

    @Test
    fun `adds ws scheme when the user omits one`() {
        assertEquals("ws://192.168.1.6:8000", normalizeWsUrl("192.168.1.6:8000"))
    }

    @Test
    fun `maps http to ws and https to wss`() {
        assertEquals("ws://192.168.1.6:8000", normalizeWsUrl("http://192.168.1.6:8000"))
        assertEquals("wss://example.com", normalizeWsUrl("https://example.com"))
    }

    @Test
    fun `is case insensitive about the scheme`() {
        assertEquals("ws://192.168.1.6:8000", normalizeWsUrl("HTTP://192.168.1.6:8000"))
        assertEquals("wss://example.com", normalizeWsUrl("HtTpS://example.com"))
        assertEquals("WS://192.168.1.6:8000", normalizeWsUrl("WS://192.168.1.6:8000"))
    }

    @Test
    fun `trims whitespace and trailing slashes`() {
        assertEquals("ws://192.168.1.6:8000", normalizeWsUrl("  ws://192.168.1.6:8000/  "))
        assertEquals("ws://192.168.1.6:8000", normalizeWsUrl("192.168.1.6:8000/"))
    }
}
