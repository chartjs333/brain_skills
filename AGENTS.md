# Codex Instructions

This repository is a personal workspace for reusable Codex skills and related agent guidance.

## Working Principles

- Keep changes small, intentional, and easy to review.
- Prefer existing repository structure before adding new directories or conventions.
- Do not commit secrets, local credentials, generated caches, or machine-specific files.
- Use clear Markdown with runnable examples when documenting a skill or workflow.

## Skill Layout

- Put reusable skills under `skills/<skill-name>/SKILL.md`.
- Add `scripts/`, `assets/`, or `references/` inside a skill directory only when the skill needs them.
- Keep each skill focused on one repeatable task or domain.
- Include validation steps when a skill creates code, documents, spreadsheets, presentations, or other artifacts.

## Validation

- Review `git diff` before committing.
- Run the most relevant local checks for the files changed.
- If a check cannot be run, mention the reason in the final handoff.
