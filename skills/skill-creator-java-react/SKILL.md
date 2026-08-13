---
name: skill-creator-java-react
description: Generate and manage queue-driven Codex skill folders with Java 17 Spring Boot and React TypeScript integration, TTL metadata, Java scheduled expiry cleanup, TTL extension handling, and validator submissions. Use when Codex is asked to act as SkillCreatorJavaReact, poll work queue endpoints with Java HttpClient, create generated skill folders like skills/skill_SEQ_msg_MESSAGE_NAME/SKILL.md, update ephemeral skill TTLs, scaffold Java backend and React frontend handlers, or submit skill lifecycle reports.
---

# Skill Creator Java React

## Overview

Use this skill to operate as `SkillCreatorJavaReact`: poll the local work queue from Java, create ephemeral Codex skills with stable sequence/message identity, wire Java and React handlers when a host app exists, maintain TTL metadata, clean expired generated skills through Java scheduled execution, and submit validation reports.

Never send `GIT CONTEXT` in request bodies or query strings. The QA server is expected to bind git context by the `{phone}` path segment.

All queue workers, backend services, skill generation logic, validator submissions, and TTL garbage collection must be implemented in Java 17+ only. Do not add Python, Node.js, or shell scripts for backend or queue processing logic.

## Queue Workflow

1. Poll `GET http://localhost:8025/work/{phone}` from `SkillQueueWorker.java` with phone `9301` unless the user supplies another value.
2. If the queue returns HTTP 400 with "Phone ... is not mapped to a Git context", resolve `git remote get-url origin` from Java and register the phone with `POST http://localhost:8025/git-config` using JSON body `{"port": 8025, "git_address": "...", "phone": "9301"}`. Then poll `/work/{phone}` again.
3. If the queue returns HTTP 404, treat the queue as empty for this phone/git context and wait before polling again when continuous operation is requested.
4. If the response is an `ACTION: EXTEND_TTL` command, extend the matching generated skill by `skill_id` or `message_id`; do not recreate the skill.
5. If the response contains a new skill task, extract or assign:
   - `seq_number`: next four-digit sequence, starting at `0001`.
   - `message_id`: the source queue message id.
   - `skill_name`: a short snake-case folder suffix.
   - `ttl_seconds`: use the requested TTL, or default to `3600`.
6. Create `skills/skill_<seq_number>_msg_<message_id>_<skill_name>/SKILL.md`.
7. Add or update Java and React artifacts in the target application only after inspecting the app's existing package, router, registry, build, and naming conventions.
8. Run the most relevant checks, start the Java and React apps on free ports when a runnable app exists, then submit a report to `POST http://localhost:8025/test/{phone}` from Java.

Never pass git context in `/work/{phone}` or `/test/{phone}` request bodies or query strings. Only `/git-config` receives the repository git address for phone mapping.

## Generated Skill Spec

Generated ephemeral skills must keep the Codex-required `name` and `description` fields and add lifecycle fields used by this workflow:

```yaml
---
name: symptom-extractor
description: Extract symptom mentions from provided clinical text.
created_at: "2026-08-13T15:30:00Z"
ttl_seconds: 3600
expires_at: "2026-08-13T16:30:00Z"
seq_number: "0001"
message_id: "msg_1042"
skill_id: "skill_0001_msg_1042_symptom_extractor"
---
```

The skill body should be self-contained: purpose, inputs, workflow, outputs, validation steps, and any Java/React endpoints or UI routes created for it.

## Java Manager

Use the bundled Java 17 Spring Boot manager for deterministic lifecycle operations:

```powershell
cd skills/skill-creator-java-react/scripts/java-skill-manager
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8080 --skill.manager.phone=9301"
```

Core local endpoints:

- `POST /api/v1/queue/register`: register phone to git context with Java HttpClient.
- `GET /api/v1/queue/poll?registerOn400=true`: poll `/work/{phone}` and self-register on HTTP 400.
- `POST /api/v1/skills`: create `skills/skill_<seq>_msg_<message_id>_<skill_name>/SKILL.md`.
- `GET /api/v1/skills`: list generated skill registry entries.
- `POST /api/v1/skills/{skillId}/execute`: execute the generated skill facade.
- `GET /api/v1/skills/{skillId}/ttl`: return Java TTL status.
- `POST /api/v1/skills/ttl/extend`: extend a generated skill by `skillId` or `messageId`.
- `POST /api/v1/skills/gc`: run TTL garbage collection once.
- `GET /api/v1/validation/report`: render the validator report.
- `POST /api/v1/validation/submit`: submit the validator report to `/test/{phone}`.

Garbage collection must only delete generated directories matching `skill_<seq>_msg_*` that contain an expired `SKILL.md` with `expires_at`.

## Java Integration

Prefer the host Spring Boot application's existing structure. When no local convention exists but a Java app is required, use a small generated package such as `com.example.skills.generated` and expose:

- `GET /api/v1/skills` for active skill registry entries.
- `POST /api/v1/skills/{skillId}/execute` for skill execution.
- `GET /api/v1/skills/{skillId}/ttl` for `created_at`, `ttl_seconds`, `expires_at`, and remaining seconds.

Implement TTL cleanup with a Spring background service or `@Scheduled` job that removes expired generated skills from the registry and disk. Keep deletion constrained to generated skill directories.

## React Integration

Prefer the host React TypeScript application's existing routing and component registry. Generated UI should expose a route like `/skills/{skill_id}`, fetch TTL status from the backend, show execution controls for the skill's expected inputs, and handle loading, success, error, and expired states.

Use a dynamic registry shape when the app has none:

```ts
export const skillComponentRegistry: Record<string, React.ComponentType> = {
  skill_0001_msg_1042_symptom_extractor: Skill0001Msg1042Component,
};
```

## Validation Submission

Submit plain text to `/test/{phone}` using this shape:

```text
TO: SkillValidator
FROM: SkillCreatorJavaReact
STATUS: READY_FOR_VALIDATION
PHONE: 9301
IMPLEMENTATION_LANGUAGE: Java 17 / Spring Boot

GENERATED_SKILL_INFO:
- Sequence Number: 0001
- Associated Message ID: msg_1042
- Skill ID: skill_0001_msg_1042_symptom_extractor
- Skill Name: symptom-extractor
- TTL Seconds: 3600
- Created At: 2026-08-13T15:30:00Z
- Expires At: 2026-08-13T16:30:00Z
- Active Skills Count in System: 3

APPLICATION_URLS:
- React UI URL: http://localhost:<react_port>
- Java Backend API URL: http://localhost:<java_port>
- Direct Skill Route: http://localhost:<react_port>/skills/skill_0001_msg_1042_symptom_extractor

LOCAL_TESTING_STATUS:
- Java Backend Running: YES/NO
- React Dev Server Running: YES/NO
- Java Scheduled TTL GC: ACTIVE/NOT_RUN
```

Include concise test scenarios covering the UI route, execution endpoint, TTL endpoint, and TTL extension or expiry behavior when applicable.
