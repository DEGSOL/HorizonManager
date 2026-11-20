"""Retry invalid profile selections at the pinned Horizon scoring boundary."""

from dataclasses import dataclass

from src.ai import analyzer
from src.ai.classifier import ContentClassifier
from src.ai.prompting.classification import classification_system_prompt, classification_user_prompt
from src.ai.utils import parse_json_response

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


async def score_with_retry(service, job, **common):
    # The pinned Horizon version hardcodes the classifier in ContentAnalyzer.
    # The worker serves one job per process, so the override is isolated.
    previous = analyzer.ContentClassifier
    analyzer.ContentClassifier = RetryingClassifier
    try:
        return await service.score_items(job, **common)
    finally:
        analyzer.ContentClassifier = previous
