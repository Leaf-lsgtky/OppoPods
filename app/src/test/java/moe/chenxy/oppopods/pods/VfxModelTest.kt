package moe.chenxy.oppopods.pods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VfxModelTest {

    @Test
    fun parsesConfigGlbAndIbl() {
        val model = VfxModel.parse(container())

        assertNotNull(model)
        val parsed = model!!
        assertEquals("test", parsed.config.name)
        assertEquals(2f, parsed.config.modelScale, 0f)
        assertEquals(123f, parsed.config.iblIntensity, 0f)
        assertEquals(4, parsed.config.materials.single().properties.single().type)
        assertTrue(parsed.glb.size > 20)
        assertNotNull(parsed.ibl)
    }

    @Test
    fun rejectsContainerWithoutGlbMagic() {
        val bytes = container()
        val marker = "glTF".toByteArray()
        val index = (0..bytes.size - marker.size).first { start ->
            marker.indices.all { bytes[start + it] == marker[it] }
        }
        bytes[index] = 0
        assertNull(VfxModel.parse(bytes))
    }

    private fun container(): ByteArray {
        val json = """{"asset":{"version":"2.0"}}""".toByteArray()
        val glb = ByteBuffer.allocate(20 + json.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("glTF".toByteArray())
            putInt(2)
            putInt(20 + json.size)
            putInt(json.size)
            put("JSON".toByteArray())
            put(json)
        }.array()
        val config = """
            {"name":"test","modelScale":2,"backgroundColor":[1,1,1,1],
             "light":{"intensity":0,"direction":[0,0,0],"iblIntensity":123,"iblAngle":[0,90,0]},
             "materials":{"materials":[{"name":"m","property":[{"name":"metallicFactor",
             "value":1,"color":[1,1,1,1],"type":4}]}]}}
        """.trimIndent().toByteArray()
        val ktx = byteArrayOf(
            0xAB.toByte(), 0x4B, 0x54, 0x58, 0x20, 0x31, 0x31,
            0xBB.toByte(), 0x0D, 0x0A, 0x1A, 0x0A,
        )
        val configStart = 40
        val glbStart = configStart + config.size
        val iblStart = glbStart + glb.size
        val total = iblStart + ktx.size
        return ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(configStart)
            putInt(config.size)
            putInt(glbStart)
            putInt(glb.size)
            putInt(iblStart)
            putInt(ktx.size)
            putInt(0)
            putInt(0)
            putInt(0)
            putInt(0)
            put(config)
            put(glb)
            put(ktx)
        }.array()
    }
}

class HeyMelodyArtworkRequestTest {

    @Test
    fun buildsSignedControlBodyInReferenceOrder() {
        assertEquals(
            """{"platform":"android","channel":"2","versionCode":"17006004","productId":"067410","color":"2"}""",
            HeyMelodyArtworkClient.buildBody("067410", 2, model = false),
        )
        assertEquals(
            """{"platform":"android","channel":"2","versionCode":"17006004","productId":"067410","color":"2","moduleId":"14"}""",
            HeyMelodyArtworkClient.buildBody("067410", 2, model = true),
        )
    }

    @Test
    fun onlyAllowsHeyMelodyImageHostsOverHttps() {
        assertTrue(
            HeyMelodyArtworkClient.isAllowedDownloadUri(
                "https://iot-earbuds-cn.heytapimage.com/earbuds/x.zip",
            ),
        )
        assertTrue(
            HeyMelodyArtworkClient.isAllowedDownloadUri(
                "https://iot-heythings-cn.heytapimage.com/heythings-static/x.zip",
            ),
        )
        assertFalse(
            HeyMelodyArtworkClient.isAllowedDownloadUri(
                "https://example.com/x.zip",
            ),
        )
        assertFalse(
            HeyMelodyArtworkClient.isAllowedDownloadUri(
                "http://iot-earbuds-cn.heytapimage.com/x.zip",
            ),
        )
    }
}
