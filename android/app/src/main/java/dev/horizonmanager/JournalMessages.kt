package dev.horizonmanager

import org.json.JSONObject
import java.text.NumberFormat

internal fun journalLevel(level: String) = when (level) {
    "ERROR", "CRITICAL" -> ui(R.string.journal_level_error)
    "WARNING" -> ui(R.string.journal_level_warning)
    "INFO" -> ui(R.string.editorial_information)
    "DEBUG" -> ui(R.string.editorial_debug)
    else -> ""
}

internal fun journalComponent(component: String) = when (component) {
    "vps" -> ui(R.string.journal_component_vps)
    "agent" -> ui(R.string.journal_component_agent)
    "android" -> ui(R.string.app_name)
    "horizon" -> "Horizon"
    "scheduler" -> ui(R.string.journal_component_scheduler)
    "delivery", "push" -> ui(R.string.journal_component_delivery)
    else -> ui(R.string.ui_all)
}

private fun jobName(kind: String) = when (kind) {
    "ai" -> ui(R.string.ui_ai_test)
    "source" -> ui(R.string.ui_source_test)
    "localize" -> ui(R.string.ui_translation)
    "article_context" -> ui(R.string.article_context_heading)
    else -> ui(R.string.ui_digest)
}

private fun installStep(detail: String) = ui(when (detail.substringBefore(" · ")) {
    "preflight" -> R.string.journal_install_preflight
    "packages" -> R.string.journal_install_packages
    "files" -> R.string.journal_install_files
    "runtime" -> R.string.journal_install_runtime
    "service" -> R.string.journal_install_service
    "health" -> R.string.journal_install_health
    else -> R.string.journal_install_default
})

/** Format only diagnosticEvent output, so free-form provider and shell messages stay outside the UI. */
internal fun journalMessage(row: JSONObject): Pair<String, String> {
    val code = row.optString("code")
    val detail = row.optString("detail")
    val phaseBody = when (code) {
        "fetch" -> R.string.journal_fetch_detail
        "score" -> R.string.journal_score_detail
        "filter" -> R.string.journal_filter_detail
        "enrich" -> R.string.journal_enrich_detail
        "generate" -> R.string.journal_generate_detail
        "save" -> R.string.journal_save_detail
        "delivery" -> R.string.journal_delivery_detail
        "media" -> R.string.journal_digest_media_detail
        else -> null
    }
    if (phaseBody != null) return phaseName(code) to ui(phaseBody)
    return when (code) {
        "INSTALL_RUNNING", "INSTALL_STARTED" -> installStep(detail) to ui(
            if (detail.endsWith(" · exit 0")) R.string.journal_install_step_ok else R.string.journal_install_running)
        "INSTALL_SUCCESS" -> ui(R.string.journal_install_ok_title) to ui(R.string.journal_install_ok)
        "INSTALL_FAILED", "INSTALL_ERROR" -> installStep(detail) to ui(R.string.journal_install_failed)
        "JOB_QUEUED" -> ui(R.string.journal_queued_title, jobName(detail)) to ui(R.string.journal_queued)
        "JOB_STARTED" -> ui(R.string.journal_digest_started) to ui(R.string.journal_digest_started_detail)
        "JOB_SUCCESS" -> {
            val tokens = jobTokenCounts(detail)
            val body = when {
                tokens != null -> {
                    val number = NumberFormat.getIntegerInstance(appLocale())
                    ui(R.string.journal_job_tokens, number.format(tokens.first), number.format(tokens.second))
                }
                detail == unavailableJobTokens -> ui(R.string.journal_job_tokens_unavailable)
                else -> ui(R.string.journal_job_ok)
            }
            ui(R.string.journal_job_ok_title) to body
        }
        "JOB_FAILED", "HORIZON_EXIT" -> ui(R.string.journal_job_failed_title) to ui(R.string.journal_job_failed)
        "JOB_CANCELLED", "STOP_REQUESTED" -> ui(R.string.journal_job_stop_title) to ui(
            if (code == "STOP_REQUESTED") R.string.journal_job_stop_requested else R.string.journal_job_stop)
        "JOB_INTERRUPTED", "INTERRUPTED_BY_RESTART", "ARTICLE_CONTEXT_INTERRUPTED" ->
            ui(R.string.journal_job_interrupted_title) to ui(R.string.journal_job_interrupted)
        "JOB_TIMEOUT" -> ui(R.string.ui_timed_out) to ui(R.string.journal_job_timeout)
        "ARTICLE_CONTEXT_STARTED" -> ui(R.string.article_context_heading) to ui(R.string.journal_context_started)
        "ARTICLE_CONTEXT_SUCCESS" -> ui(R.string.article_context_heading) to ui(R.string.journal_context_success)
        "ARTICLE_CONTEXT_FAILED", "ARTICLE_CONTEXT_TIMEOUT", "ARTICLE_CONTEXT_CANCELLED",
        "ARTICLE_CONTEXT_EMPTY", "ARTICLE_PROFILE_UNAVAILABLE" -> ui(R.string.article_context_heading) to ui(R.string.journal_context_problem)
        "ARTICLE_UNKNOWN", "RUN_UNKNOWN", "DIGEST_UNKNOWN", "JOB_UNKNOWN", "JOB_FINISHED",
        "HZ_RUN_NOT_FOUND", "HZ_STAGE_NOT_FOUND", "HZ_SUMMARY_NOT_FOUND", "HZ_INVALID_STAGE" ->
            ui(R.string.journal_missing_title) to ui(R.string.journal_missing)
        "SOURCE_SUCCESS", "SOURCE_EMPTY", "SOURCE_FAILURE", "SOURCE_PARTIAL_FAILURE" -> {
            val count = detail.substringBefore(" · ").toIntOrNull()
            val reason = detail.substringAfter(" · ", "").takeIf(String::isNotBlank)
            val result = when (code) {
                "SOURCE_SUCCESS" -> (R.string.journal_source_ok_title to R.string.journal_source_ok)
                "SOURCE_EMPTY" -> (R.string.journal_source_empty_title to R.string.journal_source_empty)
                "SOURCE_PARTIAL_FAILURE" -> (R.string.journal_source_partial_title to R.string.journal_source_partial)
                else -> (R.string.journal_source_failed_title to R.string.journal_source_failed)
            }
            val body = if (code == "SOURCE_SUCCESS" && count != null) ui(R.string.journal_source_count, count) else ui(result.second)
            ui(result.first) to listOfNotNull(body, reason?.let(::errorText)).joinToString(" ")
        }
        "SCHEDULE_SAVED" -> errorText(code) to ui(R.string.journal_schedule_changed)
        "SCHEDULE_SKIPPED_ACTIVE" -> ui(R.string.journal_schedule_skipped_title) to ui(R.string.journal_schedule_skipped)
        "SCHEDULER_ERROR" -> ui(R.string.journal_component_scheduler) to ui(R.string.journal_schedule_problem)
        "CONFIG_SAVED", "CONFIG_REVERTED" -> ui(R.string.journal_config_title) to ui(
            if (code == "CONFIG_SAVED") R.string.journal_config_saved_detail else R.string.journal_config_reverted)
        "MEDIA_READY" -> errorText(code) to (detail.toIntOrNull()?.let { ui(R.string.journal_media_count, it) } ?: ui(R.string.journal_media_ready_detail))
        "MEDIA_UNAVAILABLE" -> errorText(code) to ui(R.string.journal_media_unavailable_detail)
        "MEDIA_SEARCH_CHANGED" -> errorText(code) to ui(R.string.mr_search_detail)
        "DIGEST_LANGUAGE_CHANGED" -> ui(R.string.digest_output_language) to ui(R.string.digest_language_detail)
        "ENRICHMENT_MODE_CHANGED" -> ui(R.string.enrichment_mode) to ui(R.string.setting_help_enrichment)
        "ENRICHMENT_FAILED" -> ui(R.string.article_context_heading) to ui(R.string.journal_enrich_failed)
        "HEADLINE_REWRITE_FALLBACK" -> ui(R.string.journal_headline_fallback_title) to
            ui(R.string.journal_headline_fallback_count, detail.toIntOrNull() ?: 0)
        "DIGEST_DUPLICATES_REMOVED" -> ui(R.string.journal_duplicates_title) to
            ui(R.string.journal_duplicates_count, detail.toIntOrNull() ?: 0)
        "DEDUPE_PARTIAL" -> ui(R.string.journal_duplicates_title) to ui(R.string.journal_duplicates_partial)
        "LOCALIZATION_RECOVERED" -> errorText(code) to ui(R.string.journal_localization_recovered)
        "BACKUP_EXPORTED", "BACKUP_RESTORED" -> ui(R.string.ui_backup) to ui(
            if (code == "BACKUP_EXPORTED") R.string.journal_backup_exported else R.string.journal_backup_restored)
        "JOURNAL_CLEARED" -> ui(R.string.journal) to ui(R.string.journal_cleared_detail)
        "PUSH_DELIVERY_FAILED", "PUSH_ENDPOINT", "PUSH_LIMIT", "BACKGROUND_CHECK_FAILED" ->
            ui(R.string.settings_notifications) to ui(R.string.journal_push_problem)
        "CLASSIFIER_FALLBACK", "INVALID_FORMAT" -> if (code == "CLASSIFIER_FALLBACK" || detail == "src.ai.classifier · fallback")
            ui(R.string.ui_ai_scoring) to ui(R.string.journal_classifier_detail)
            else describeFailure(code, row)
        else -> describeFailure(code, row)
    }
}

private fun describeFailure(code: String, row: JSONObject): Pair<String, String> {
    val existing = errorText(code)
    if (existing != ui(R.string.ui_operation_details)) {
        val extra = when {
            code.startsWith("LOCALIZATION_") -> R.string.journal_translation_guide
            code.startsWith("SSH_") || code.startsWith("HTTPS_") || code.startsWith("AGENT_") ->
                R.string.journal_connection_guide
            code.startsWith("PROFILE_") || code.startsWith("CONFIG_") -> R.string.journal_config_action
            else -> null
        }
        return existing to ui(extra ?: R.string.journal_check_related)
    }
    val action = when {
        code.startsWith("ROUTER_") || code == "EMPTY_AI_RESPONSE" -> R.string.journal_access_model
        code in setOf("SECRET_VALUE_INVALID", "HZ_SECRETS_INVALID", "HZ_SECRETS_NOT_FOUND") -> R.string.secrets_custom_help
        code.startsWith("VAULT_") -> R.string.journal_vault_guide
        code == "BACKUP_VERSION" -> R.string.journal_backup_version
        code in setOf("AI_CHAIN", "AI_BASE_URL", "AI_CONCURRENCY", "AI_LIMIT", "COLLECTION_LIMIT",
            "PROFILE_COUNT", "PROFILE_PATH", "PROFILE_TOOL", "SECRET_NAME", "HZ_CONFIG_INVALID", "HZ_CONFIG_NOT_FOUND") ->
            R.string.journal_config_action
        code == "UPSTREAM_FAILURE" -> R.string.journal_upstream_failure
        code in setOf("REQUEST_INVALID", "BODY_LIMIT", "RESPONSE_LIMIT", "JOB_ID_CONFLICT",
            "DIGEST_LANGUAGE_FIXED", "HZ_EMPTY_INPUT", "HZ_INVALID_INPUT", "HZ_RUN_ID_EXISTS") -> R.string.journal_request_guide
        code in setOf("SOURCE_INDEX", "SOURCE_MISSING", "SOURCE_UNKNOWN") -> R.string.journal_source_failed
        code in setOf("AGENT_PROTOCOL", "AGENT_UPDATE_REQUIRED", "SSH_STAGE", "HZ_HORIZON_NOT_FOUND", "HZ_INVALID_HORIZON_PATH") ->
            R.string.journal_connection_guide
        code == "EXISTING_CONFIG" || code == "EXISTING_PATH" -> R.string.journal_request_guide
        else -> null
    }
    return when {
        action != null -> ui(R.string.journal_operation_issue) to ui(action)
        journalLevel(row.optString("level")) in listOf(ui(R.string.journal_level_error), ui(R.string.journal_level_warning)) ->
            ui(R.string.journal_operation_issue) to ui(R.string.journal_unknown_issue)
        else -> ui(R.string.journal_activity_title) to ui(R.string.journal_unknown_info)
    }
}
