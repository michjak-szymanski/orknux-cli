// Copyright (C) 2026 Michał Szymański
// SPDX-License-Identifier: AGPL-3.0-or-later
// See NOTICE for the additional term under section 7(b): the attribution this
// program prints must be preserved.

package io.mszymanski.orknux.cli

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SystemConsoleTest {

    private val realStdin: InputStream = System.`in`

    @AfterEach
    fun restoreStdin() = System.setIn(realStdin)

    @Test
    fun `reads a piped password`() {
        System.setIn(ByteArrayInputStream("hunter2\n".toByteArray()))

        assertEquals("hunter2", SystemConsole.readPipedLine())
    }

    /** Windows PowerShell prefixes what it pipes to a native process with a UTF-8 BOM. */
    @Test
    fun `drops the byte order mark PowerShell prepends`() {
        System.setIn(ByteArrayInputStream(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hunter2\r\n".toByteArray()))

        assertEquals("hunter2", SystemConsole.readPipedLine())
    }

    @Test
    fun `reports nothing piped as nothing`() {
        System.setIn(ByteArrayInputStream(ByteArray(0)))

        assertNull(SystemConsole.readPipedLine())
    }
}
