package org.praxisog.hermes.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shapes here are verbatim from the Gate 0c probes recorded in
 * `mock-hermes/fixtures/terminal-tool-results.json`.
 */
class ToolResultTest {

    @Test fun parsesAPlainSuccessfulResult() {
        val result = ToolResult.parse("""{"output": "hermes", "exit_code": 0, "error": null}""")!!
        assertEquals("hermes", result.output)
        assertEquals(0, result.exitCode)
        assertTrue(result.succeeded)
        assertFalse(result.isBlocked)
        assertFalse(result.wasFlaggedAndAllowed)
    }

    @Test fun surfacesTheAutoApprovalAuditNote() {
        // The case that matters: a recursive delete that ran anyway. If this note is dropped,
        // a destructive auto-approved command is invisible to the user.
        val json = """{"output": "", "exit_code": 0, "error": null,
            "approval": "Command was flagged (recursive delete) and auto-approved by smart approval."}"""
        val result = ToolResult.parse(json)!!
        assertTrue(result.wasFlaggedAndAllowed)
        assertFalse(result.isBlocked)
        assertTrue(result.approval!!.contains("recursive delete"))
    }

    @Test fun blockedIsDistinctFromAnError() {
        val result = ToolResult.parse(
            """{"output": "", "exit_code": -1, "error": "Command denied: dangerous command.", "status": "blocked"}"""
        )!!
        assertTrue(result.isBlocked)
        assertFalse("a refusal is not a success", result.succeeded)
    }

    @Test fun nonJsonContentIsNotAFailure() {
        // Some tools return prose. Null means "render as text", not "the call broke".
        assertNull(ToolResult.parse("Fixture tool result."))
        assertNull(ToolResult.parse(null))
        assertNull(ToolResult.parse("   "))
    }

    @Test fun unknownFieldsDoNotBreakDecoding() {
        val result = ToolResult.parse("""{"output": "x", "exit_code": 0, "future_field": {"a": 1}}""")!!
        assertEquals("x", result.output)
    }
}
