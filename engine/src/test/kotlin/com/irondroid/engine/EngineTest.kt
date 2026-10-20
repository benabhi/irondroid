package com.irondroid.engine

import kotlin.test.Test
import kotlin.test.assertTrue

/** Sample test that keeps the engine test setup running until the real rules arrive. */
class EngineTest {
    @Test
    fun `version follows semantic versioning`() {
        assertTrue(Engine.VERSION.matches(Regex("""\d+\.\d+\.\d+""")), "Unexpected version ${Engine.VERSION}")
    }
}
