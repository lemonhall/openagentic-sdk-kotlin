package me.lemonhall.openagentic.sdk.e2e

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class OfflineChecklistNamesTest {
    @Test
    fun offline_checklist_recommended_test_names_exist() {
        val expected =
            listOf(
                "offline_events_jsonl_roundtrip_unicode",
                "offline_events_no_delta_persistence",
                "offline_events_call_id_bijection",
                "offline_events_strict_required_fields",
                "offline_events_redaction_no_secrets",
                "offline_events_unknown_fields_forward_compat",
                "offline_events_seq_monotonic",
                "offline_events_dedup_on_retry",
                "offline_loop_zero_tool_calls",
                "offline_loop_single_tool_call_success",
                "offline_loop_multi_tool_calls_serial",
                "offline_loop_tool_raises_exception",
                "offline_loop_tool_returns_non_json",
                "offline_loop_max_tool_calls_fuse",
                "offline_loop_cancel_mid_run_no_partial_jsonl",
                "offline_loop_timeout_provider_vs_tool_classification",
                "offline_loop_unhandled_exception_becomes_error_event",
                "offline_tool_args_missing_field",
                "offline_tool_args_wrong_type",
                "offline_tool_args_unknown_properties",
                "offline_tool_args_json_string_instead_of_object",
                "offline_tool_output_large_payload_truncate_or_summarize",
                "offline_tool_registry_duplicate_name_policy",
                "offline_allowed_tools_enforced_across_turns",
                "offline_allowed_tools_preserved_after_compaction",
                "offline_permission_allow_all",
                "offline_permission_deny_records_reason",
                "offline_permission_prompt_no_answerer_fails_fast",
                "offline_permission_prompt_answerer_happy_path",
                "offline_permission_default_deny_on_schema_parse_error",
                "offline_permission_scope_precedence",
                "offline_hooks_before_model_call_mutates_messages",
                "offline_hooks_pre_tool_use_mutates_args",
                "offline_hooks_order_is_stable",
                "offline_hooks_exception_is_recorded_and_isolated",
                "offline_hooks_cannot_bypass_permissions",
                "offline_session_resume_continues_without_replaying_side_effect_tool",
                "offline_session_truncated_line_recovery_policy",
                "offline_session_concurrent_sessions_isolation",
                "offline_session_custom_home_dir",
                "offline_session_unicode_paths",
                "offline_compaction_trigger_and_records_event",
                "offline_compaction_preserves_permissions_and_allowed_tools",
                "offline_provider_timeout_is_classified",
                "offline_provider_rate_limit_backoff_uses_fake_clock",
                "offline_provider_invalid_json_response_is_handled",
                "offline_provider_stream_parse_half_packet",
                "offline_security_path_traversal_blocked",
                "offline_security_symlink_escape_blocked",
                "offline_security_ssrf_blocked_default",
                "offline_security_command_injection_not_possible",
                "offline_security_control_chars_do_not_break_jsonl",
            )

        val root = Path.of("src", "test", "kotlin")
        val allText =
            Files.walk(root).use { stream ->
                stream
                    .filter { it.toString().endsWith(".kt") }
                    .map { Files.readString(it) }
                    .toList()
                    .joinToString("\n")
            }

        for (name in expected) {
            val rx = Regex("""fun\s+$name\s*\(""")
            assertTrue(rx.containsMatchIn(allText), "missing checklist-aligned test name: $name")
        }
    }
}

