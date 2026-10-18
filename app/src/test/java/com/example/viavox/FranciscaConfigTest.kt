package com.example.viavox

import com.example.viavox.model.ViaVoxConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FranciscaConfigTest {
    @Test
    fun franciscaRequiresBothSecureBrokerAndToken() {
        assertFalse(ViaVoxConfig().hasFranciscaSettings())
        assertFalse(ViaVoxConfig(franciscaBrokerUrl = "https://voice.example/tts").hasFranciscaSettings())
        assertFalse(ViaVoxConfig(franciscaAccessToken = "example-token").hasFranciscaSettings())
        assertFalse(ViaVoxConfig(franciscaBrokerUrl = "http://insecure.example/tts", franciscaAccessToken = "token").hasFranciscaSettings())
        assertTrue(ViaVoxConfig(franciscaBrokerUrl = "https://voice.example/tts", franciscaAccessToken = "token").hasFranciscaSettings())
    }
}
