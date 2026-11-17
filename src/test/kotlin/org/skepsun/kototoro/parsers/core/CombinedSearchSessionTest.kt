package org.skepsun.kototoro.parsers.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CombinedSearchSessionTest {
    @Test
    fun `a repeated website page fails rather than looping or silently truncating results`() = runTest {
        val session = CombinedSearchSession(listOf<suspend (Int) -> CombinedSearchPage<Int>>(
            { CombinedSearchPage(listOf(1), true) },
        ), Int::toString, Int::toLong) { false }
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { session.read(0, 1) } }
    }

    @Test
    fun `filtered empty pages do not stop cursors and result offsets do not become website pages`() = runTest {
        val pages = mutableListOf<Int>()
        val session = CombinedSearchSession(listOf<suspend (Int) -> CombinedSearchPage<Int>>({ page ->
            pages += page
            CombinedSearchPage(listOf(12 - page * 2, 11 - page * 2), page < 3)
        }), Int::toString, Int::toLong) { it % 3 == 0 }
        assertEquals(listOf(12, 9), session.read(0, 2))
        assertEquals(listOf(6), session.read(2, 2))
        assertEquals(listOf(0, 1, 2, 3), pages)
        assertEquals(listOf(12, 9), session.read(0, 2))
        assertEquals(4, pages.size)
    }

    @Test
    fun `multiple query branches merge by common rank and remove duplicates across pages`() = runTest {
        val session = CombinedSearchSession(listOf<suspend (Int) -> CombinedSearchPage<Int>>(
            { p -> CombinedSearchPage(if (p == 0) listOf(9, 7) else listOf(5), p == 0) },
            { p -> CombinedSearchPage(if (p == 0) listOf(9, 8) else listOf(5, 4), p == 0) },
        ), Int::toString, Int::toLong) { true }
        assertEquals(listOf(9, 8, 7), session.read(0, 3))
        assertEquals(listOf(5, 4), session.read(3, 3))
        assertTrue(session.read(5, 3).isEmpty())
        assertTrue(session.read(99, 3).isEmpty())
    }

    @Test
    fun `failed candidate stays pending and can be retried without skipping it`() = runTest {
        var fail = true
        val session = CombinedSearchSession(listOf<suspend (Int) -> CombinedSearchPage<Int>>(
            { CombinedSearchPage(listOf(3, 2, 1), false) },
        ), Int::toString, Int::toLong) {
            if (it == 2 && fail) throw IllegalStateException("candidate failed")
            true
        }
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { session.read(0, 3) } }
        fail = false
        assertEquals(listOf(3, 2, 1), session.read(0, 3))
    }

    @Test
    fun `cancellation does not consume the pending candidate`() = runTest {
        var cancel = true
        val session = CombinedSearchSession(listOf<suspend (Int) -> CombinedSearchPage<Int>>(
            { CombinedSearchPage(listOf(1), false) },
        ), Int::toString, Int::toLong) {
            if (cancel) throw CancellationException("stop")
            true
        }
        assertThrows(CancellationException::class.java) { kotlinx.coroutines.runBlocking { session.read(0, 1) } }
        cancel = false
        assertEquals(listOf(1), session.read(0, 1))
    }
}
