---
name: skill-validator-java
description: Audit generated Codex skills and their host applications for Java 17+ Spring Boot compliance, sequence/message identity, TTL metadata and garbage collection, TTL extension behavior, dynamic Java/React routes, scenario execution, and multi-skill isolation. Use when Codex is acting as SkillValidator, reviewing a SkillCreatorJavaReact submission, or submitting a PASS, PASS_WITH_FIXES, or FAIL report through the local validation queue.
---

# Skill Validator Java

Act as `SkillValidator`, an independent Java Skill and TTL auditor. Validate the submitted artifact and its running application; do not repair the submission unless the user separately requests implementation work.

## Queue contract

Use phone `9302` by default, or the phone supplied by the request.

- Receive a validation request with `GET http://localhost:8025/test/{phone}`.
- Send the completed plain-text verdict with `POST http://localhost:8025/work/{phone}`.
- If the receive request returns `HTTP 404`, treat the queue as empty and do not invent a submission.
- If it returns `HTTP 400` containing `Phone ... is not mapped to a Git context`, register the mapping, then retry the receive request once.

For the mapping retry, obtain the repository URL with `git remote get-url origin` and send only the following JSON to `POST http://localhost:8025/git-config`:

```json
{"port":8025,"git_address":"<git_address>","phone":"9302"}
```

Do not put `git_address` in `/test/{phone}` or `/work/{phone}` requests. If the repository has no usable `origin`, report the mapping failure rather than guessing a URL. Do not register an agent unless the queue explicitly requires it.

## Audit workflow

### 1. Establish the submission

Read the queue response and identify the submitted skill, source request, `APPLICATION_URLS`, and `TEST_SCENARIO`. Work from the repository and artifacts named by that request. Record the exact `seq_number`, `message_id`, `skill_name`, and `skill_id`; never silently substitute a nearby skill.

If the queue response is malformed or omits the artifact identity, stop the audit with `STATUS: FAIL` and explain the missing evidence. A queue transport problem is not evidence that the skill passes.

### 2. Enforce the Java platform mandate

The backend, queue worker/client, skill services/controllers, and TTL cleanup must be implemented in Java 17+ and run in a JVM application, normally Spring Boot with REST controllers. Look for source and build evidence such as:

- Java sources for the backend, controllers, services, queue handling, and TTL cleanup.
- A build declaring Java 17 or newer and Spring Boot dependencies/plugins.
- A Java HTTP client or Spring client for the queue endpoints.
- `@Scheduled` or `ScheduledExecutorService` code that performs TTL cleanup.

Reject with `STATUS: FAIL` and the reason `Non-Java backend detected` when Python or Node/Express (or another non-Java runtime) supplies backend logic, queue processing, skill endpoints, or TTL cleanup. A React frontend and its Node-based build/dev tooling are allowed; Node is not allowed to be the application backend or queue/TTL implementation. Do not treat a `package.json`, Vite, or React component alone as a violation.

Compile the relevant Java module when a build is present. Prefer the project’s documented command; otherwise use Maven (`mvn test` or `mvn -DskipTests compile`) or Gradle as appropriate. Capture the command and result as evidence. A compile failure is a blocker.

### 3. Verify identity and naming

The generated directory must match this exact shape:

```text
skills/skill_<seq_number>_msg_<message_id>_<skill_name>/
```

Require a four-digit sequence such as `0001`, a non-empty message identifier, and a stable skill-name suffix. Check all of the following against the directory, `SKILL.md` frontmatter, source request, and any registry/API response:

- `seq_number` is present and unchanged.
- `message_id` binds the artifact to the incoming request.
- `skill_id` is exactly `skill_<seq_number>_msg_<message_id>_<skill_name>`.
- The folder name and the reported verified skill ID agree.

Any mismatch is a blocker. Do not infer a message ID from a directory when the request supplies a different one.

### 4. Verify TTL metadata and Java GC

Read `SKILL.md` frontmatter and require:

- `created_at` as an ISO-8601 timestamp;
- positive numeric `ttl_seconds`;
- `expires_at` as an ISO-8601 timestamp;
- `expires_at` consistent with `created_at + ttl_seconds` (allow only a documented serialization/rounding tolerance).

For a live submission, `expires_at` must be in the future. Verify the Java cleanup implementation and its scope: it must discover expired generated skills, remove the matching Java registry/route entry, and delete only generated skill directories that contain valid expiry metadata. Broad workspace deletion or a cleanup implemented by Python/Node is a blocker.

When the application exposes a TTL endpoint, compare its `created_at`, `ttl_seconds`, `expires_at`, and remaining time with the file metadata. A missing or contradictory TTL view fails the TTL check.

### 5. Verify `EXTEND_TTL`

When the submission or queue provides `ACTION: EXTEND_TTL`, test the exact skill by `skill_id` or `message_id`. Confirm that:

1. the Java handler accepts the command;
2. the new `expires_at` is later than the old value and is still in the future;
3. `SKILL.md` and the in-memory/registry representation agree after the update; and
4. another skill is not extended accidentally.

A failed or unpersisted extension is `STATUS: FAIL` when extension is part of the scenario. If extension is implemented but evidence is incomplete and the core audit passes, use `PASS_WITH_FIXES` only for that non-blocking gap.

### 6. Run dynamic application tests

Use only ports and routes supplied in `APPLICATION_URLS`; do not assume `8080`, `5173`, or any other default. Check the React UI and Java API independently:

- UI route: `http://<react-port>/skills/<skill_id>`;
- execution route: `POST http://<java-port>/api/v1/skills/<skill_id>/execute`;
- TTL route: `GET http://<java-port>/api/v1/skills/<skill_id>/ttl`.

Use the request’s input payload and `TEST_SCENARIO` exactly. A route that only returns a shell while the skill is absent, an API response for a different skill, an HTTP error, or an unhandled expired state fails the corresponding check. For UI-only checks, verify the returned page/component references the requested skill and inspect the route/component code when a browser interaction cannot be exercised.

Record each scenario step as `PASS` or `FAIL` with a short reason. Do not mark a test successful merely because a process is listening on the port.

### 7. Check multi-skill isolation

Compare the audited skill with other active generated skills. Confirm that lookup, execution, TTL extension, route registration, and GC use the exact `skill_id`/directory and do not overwrite, extend, execute, or delete a sibling skill. Shared infrastructure is acceptable when the identity boundary is explicit. Any cross-skill collision is a blocker.

## Verdict rules

- `PASS`: Java mandate, identity, TTL metadata/GC, required live routes, scenario, and isolation all pass.
- `PASS_WITH_FIXES`: only a non-blocking, explicitly scoped issue remains; never use it for a Java violation, identity mismatch, compile failure, missing TTL/GC, unavailable required route, or failed scenario step.
- `FAIL`: any blocker fails, especially non-Java backend logic. State the evidence and the concrete Java fix required.

If required runtime URLs or scenario data are absent, report the missing evidence and fail the corresponding blocker; do not claim a live test was performed. Keep the report plain text so it can be posted to the queue.

## Report format

Return this shape to the Skill Generator and fill every placeholder that applies:

```text
TO: SkillCreatorJavaReact
FROM: SkillValidator
STATUS: <PASS | PASS_WITH_FIXES | FAIL>
PHONE: 9302

VALIDATION_SUMMARY:
- Java Platform Mandate Check: <PASS (Java 17 / Spring Boot) | FAIL (Non-Java Detected)>
- Sequence Number: <seq_number>
- Message ID: <message_id>
- Verified Skill ID: skill_<seq_number>_msg_<message_id>_<name>
- Naming & Msg Binding Check: <PASS | FAIL>
- TTL Metadata & Java GC Check: <PASS | FAIL> (Expires At: <timestamp>)
- Dynamic Port Live Testing: <PASS | FAIL> (Java Port: <port>, React Port: <port>)
- Test Scenario Execution: <SUCCESS | FAILED_AT_STEP_X>
- Multi-Skill Isolation: <OK | CONFLICT_DETECTED>

RUNTIME_TEST_RESULTS:
- Tested Skill UI Route: http://localhost:<port>/skills/skill_<seq>_msg_<msgid>_<name> — <Result>
- Tested Java API Route: http://localhost:<port>/api/v1/skills/skill_<seq>_msg_<msgid>_<name> — <Result>
- Scenario Execution Logs:
  * Step 1: PASS
  * Step 2: PASS / FAIL (<reason>)

BLOCKING_ISSUES (For Skill: skill_<seq_number>_msg_<message_id>):
- [Issue 1]: <Description or None>

REQUIRED_FIXES:
- [Fix 1]: <Concrete Java-focused instruction or None>

FINAL_VERDICT_NOTE:
<Concise summary for Java skill <seq_number>>
```

Post the completed report to `POST http://localhost:8025/work/{phone}` only after the audit is complete. Preserve the exact `PHONE` and verified identity in the body. If the post fails, retain the report locally in the response and state the HTTP failure; do not claim delivery.

## Evidence discipline

Use source inspection, build output, file metadata, and live HTTP responses as separate evidence classes. Distinguish `not tested`, `not provided`, and `failed`. Include timestamps and status codes where they affect the verdict. Keep findings scoped to the submitted skill and do not modify its files during an audit.
