"""Retry invalid profile selections at the pinned Horizon scoring boundary."""

import re
from dataclasses import dataclass

from src.ai import analyzer
from src.ai.classifier import ContentClassifier
from src.ai.prompting.analysis import analysis_system_prompt as upstream_analysis_prompt
from src.ai.prompting.classification import classification_system_prompt, classification_user_prompt
from src.ai.utils import parse_json_response
from src.processing.profiles import BUILTIN_PROFILES_DIR, ProfileRegistry

from .configuration import DEFAULT_SUMMARY_INSTRUCTION

SUMMARY_FIELD = re.compile(r'^([ \t]*"summary"\s*:\s*)"[^"\n]+"(?=[ \t]*,?[ \t]*$)', re.MULTILINE)


def news_analysis_prompt(profile, instruction, language):
    policy, heading, contract = upstream_analysis_prompt(profile).rpartition("\n# Output contract\n")
    description = "<self-contained news article>"
    if instruction == DEFAULT_SUMMARY_INSTRUCTION:
        description = ("<self-contained news article of flexible length, preserving all relevant facts and details "
                       "from the original publication, without unnecessary skepticism, repetition, or invented information>")
    updated, count = SUMMARY_FIELD.subn(lambda match: f'{match[1]}"{description}"', contract)
    if not heading or count != 1:
        raise ValueError("HORIZON_ANALYSIS_CONTRACT_CHANGED")
    return (f"{policy}\n# Writing the news text (summary only)\n\n{instruction}\n"
            f"Write summary and reason in {language}. Keep the score rationale in reason only.\n"
            f"{heading}{updated}")


def check_analysis_contract():
    profile = ProfileRegistry.load(BUILTIN_PROFILES_DIR, "tech-news").get("tech-news")
    news_analysis_prompt(profile, DEFAULT_SUMMARY_INSTRUCTION, "Russian")


@dataclass(frozen=True)
class RecoveredSelection:
    profile: str
    confidence: None = None
    reason: None = None


class RetryingClassifier(ContentClassifier):
    async def _classify(self, item, candidate_ids=None):
        try:
            return await super()._classify(item, candidate_ids)
        except ValueError as exc:
            if not str(exc).startswith(("classifier did not return an object", "invalid classifier response",
                                         "classifier selected profile outside the allowed catalog:")):
                raise

        allowed = tuple(candidate_ids) if candidate_ids is not None else tuple(sorted(self.profiles.ids))
        # The retry asks only for the selection. Horizon still validates it
        # and uses its configured fallback when the provider cannot recover.
        system = (classification_system_prompt() +
                  '\nReturn one JSON object containing only "profile". Valid IDs: ' + ", ".join(allowed))
        user = classification_user_prompt(item, self.profiles, candidate_ids).rsplit("\nReturn:\n", 1)[0]
        response = await self.client.complete(system=system, user=user)
        parsed = parse_json_response(response)
        profile = parsed.get("profile") if isinstance(parsed, dict) else None
        if not isinstance(profile, str) or profile.strip() not in allowed:
            raise ValueError("invalid classifier response after retry")
        # A profile-only response contains no calibrated confidence or reason.
        return RecoveredSelection(profile.strip())


async def score_with_retry(service, job, *, summary_settings=None, language="English", **common):
    # The pinned Horizon version hardcodes the classifier in ContentAnalyzer.
    # The worker serves one job per process, so the override is isolated.
    previous = analyzer.ContentClassifier
    previous_prompt = analyzer.analysis_system_prompt
    analyzer.ContentClassifier = RetryingClassifier
    if summary_settings is not None:
        def build_prompt(profile):
            mode, instruction = summary_settings.get(profile.id, ("news", DEFAULT_SUMMARY_INSTRUCTION))
            if mode == "original":
                return previous_prompt(profile)
            return news_analysis_prompt(profile, instruction, language)

        analyzer.analysis_system_prompt = build_prompt
    try:
        return await service.score_items(job, **common)
    finally:
        analyzer.ContentClassifier = previous
        analyzer.analysis_system_prompt = previous_prompt
