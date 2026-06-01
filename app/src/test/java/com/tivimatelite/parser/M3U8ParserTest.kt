package com.tivimatelite.parser

import com.tivimatelite.model.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class M3U8ParserTest {

    @Test
    fun `parse EXTINF line with all attributes`() = runTest {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV 1" tvg-logo="http://logo" group-title="央视",CCTV 1
            http://example.com/live.m3u8
        """.trimIndent()
        val input = ByteArrayInputStream(m3u.toByteArray())
        val result = M3U8Parser.parse(input).toList()
        assertEquals(1, result.size)
        assertEquals("CCTV 1", result[0].name)
        assertEquals("http://logo", result[0].logoUrl)
        assertEquals("央视", result[0].groupName)
        assertEquals("http://example.com/live.m3u8", result[0].streamUrl)
    }

    @Test
    fun `parse simple channel line name comma URL format`() = runTest {
        val m3u = """
            #EXTM3U
            CCTV 1,http://example.com/live.m3u8
        """.trimIndent()
        val input = ByteArrayInputStream(m3u.toByteArray())
        val result = M3U8Parser.parse(input).toList()
        assertEquals(1, result.size)
        assertEquals("CCTV 1", result[0].name)
        assertNull(result[0].logoUrl)
        assertNull(result[0].groupName)
        assertEquals("http://example.com/live.m3u8", result[0].streamUrl)
    }

    @Test
    fun `parse EXTINF line with missing optional attributes`() = runTest {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV 1",CCTV 1
            http://example.com/live.m3u8
        """.trimIndent()
        val input = ByteArrayInputStream(m3u.toByteArray())
        val result = M3U8Parser.parse(input).toList()
        assertEquals(1, result.size)
        assertEquals("CCTV 1", result[0].name)
        assertNull(result[0].logoUrl)
        assertNull(result[0].groupName)
        assertEquals("http://example.com/live.m3u8", result[0].streamUrl)
    }

    @Test
    fun `parse ignores blank lines and non EXTINF comments`() = runTest {
        val m3u = """
            #EXTM3U

            # some comment
            #EXTINF:-1 tvg-name="CCTV 1",CCTV 1
            http://example.com/1.m3u8

            # another comment
            CCTV 2,http://example.com/2.m3u8
        """.trimIndent()
        val input = ByteArrayInputStream(m3u.toByteArray())
        val result = M3U8Parser.parse(input).toList()
        assertEquals(2, result.size)
        assertEquals("CCTV 1", result[0].name)
        assertEquals("http://example.com/1.m3u8", result[0].streamUrl)
        assertEquals("CCTV 2", result[1].name)
        assertEquals("http://example.com/2.m3u8", result[1].streamUrl)
    }

    @Test
    fun `parse empty stream returns empty list`() = runTest {
        val m3u = """
            #EXTM3U
        """.trimIndent()
        val input = ByteArrayInputStream(m3u.toByteArray())
        val result = M3U8Parser.parse(input).toList()
        assertEquals(0, result.size)
    }

    @Test
    fun `parse handles Chinese characters spaces and unicode in channel names`() = runTest {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV 第一剧场" tvg-logo="http://logo/中国" group-title="央视",CCTV 第一剧场
            http://example.com/1.m3u8
            频道 with spaces,http://example.com/2.m3u8
        """.trimIndent()
        val input = ByteArrayInputStream(m3u.toByteArray())
        val result = M3U8Parser.parse(input).toList()
        assertEquals(2, result.size)

        assertEquals("CCTV 第一剧场", result[0].name)
        assertEquals("http://logo/中国", result[0].logoUrl)
        assertEquals("央视", result[0].groupName)
        assertEquals("http://example.com/1.m3u8", result[0].streamUrl)

        assertEquals("频道 with spaces", result[1].name)
        assertNull(result[1].logoUrl)
        assertNull(result[1].groupName)
        assertEquals("http://example.com/2.m3u8", result[1].streamUrl)
    }

    @Test
    fun `parse multiple EXTINF channels`() = runTest {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV 1" tvg-logo="http://logo/1.png" group-title="央视",CCTV 1
            http://example.com/1.m3u8
            #EXTINF:-1 tvg-name="CCTV 2" tvg-logo="http://logo/2.png" group-title="央视",CCTV 2
            http://example.com/2.m3u8
            #EXTINF:-1 tvg-name="CCTV 3" tvg-logo="http://logo/3.png" group-title="央视",CCTV 3
            http://example.com/3.m3u8
        """.trimIndent()
        val input = ByteArrayInputStream(m3u.toByteArray())
        val result = M3U8Parser.parse(input).toList()
        assertEquals(3, result.size)
        assertEquals("CCTV 1", result[0].name)
        assertEquals("http://example.com/1.m3u8", result[0].streamUrl)
        assertEquals("央视", result[0].groupName)
        assertEquals("CCTV 2", result[1].name)
        assertEquals("http://example.com/2.m3u8", result[1].streamUrl)
        assertEquals("CCTV 3", result[2].name)
        assertEquals("http://example.com/3.m3u8", result[2].streamUrl)
    }

    @Test
    fun `parse EXTINF sets epgText from groupName falling back to default`() = runTest {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV 1" group-title="央视",CCTV 1
            http://example.com/1.m3u8
            #EXTINF:-1 tvg-name="CCTV 2",CCTV 2
            http://example.com/2.m3u8
        """.trimIndent()
        val input = ByteArrayInputStream(m3u.toByteArray())
        val result = M3U8Parser.parse(input).toList()
        assertEquals(2, result.size)
        assertEquals("央视", result[0].epgText)
        assertEquals("No EPG data", result[1].epgText)
    }
}
