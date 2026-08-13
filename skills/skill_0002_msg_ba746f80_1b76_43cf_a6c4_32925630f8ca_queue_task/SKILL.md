---
name: "queue-task"
description: "D:\\nginx-qa\\screenshot_folders\\screenshot_folder_18"
created_at: "2026-08-13T16:54:21.701048800Z"
ttl_seconds: 3600
expires_at: "2026-08-13T17:54:21.701048800Z"
seq_number: "0002"
message_id: "msg_ba746f80_1b76_43cf_a6c4_32925630f8ca"
skill_id: "skill_0002_msg_ba746f80_1b76_43cf_a6c4_32925630f8ca_queue_task"
---

# Queue Task

## Purpose

Execute the queue request associated with `msg_ba746f80_1b76_43cf_a6c4_32925630f8ca` while this Java-generated ephemeral skill is active.

## Source Request

```text
D:\nginx-qa\screenshot_folders\screenshot_folder_18

Да, интерфейс уже соответствует вашей идее: загрузка оригинального `.md`, выбор оригинала и создание нескольких вариаций. Но сейчас загрузка ломается на фронтенде:

```text
Cannot read properties of null (reading 'reset')
```

Вероятная причина — после асинхронной загрузки код вызывает:

```jsx
event.currentTarget.reset();
```

К этому моменту `event.currentTarget` уже равен `null`. Нужно сохранить форму до `await`:

```jsx
async function handleUpload(event) {
  event.preventDefault();
  const form = event.currentTarget;

  try {
    await uploadOriginal(selectedFile);
    form.reset();
    setSelectedFile(null);
    await refreshOriginals();
  } catch (error) {
    setError(error.message);
  }
}
```

Ещё надёжнее использовать `ref`:

```jsx
const formRef = useRef(null);

// ...
<form ref={formRef} onSubmit={handleUpload}>
// ...
formRef.current?.reset();
```

Также после успешной загрузки программа должна:

* сохранить загруженный файл как неизменяемый оригинал;
* обновить список `Originals`;
* увеличить счётчик оригиналов;
* автоматически выбрать загруженный скилл;
* показать его содержимое;
* разрешить генерацию AI-вариаций;
* не выводить техническую ошибку пользователю, если загрузка на сервер уже состоялась.

Приложенный текст — журнал прежней проверки, а не исходный React-код, поэтому непосредственно исправить программу по нему нельзя. Для внесения исправления нужен проект либо хотя бы React-компонент, содержащий `handleUpload` и вызов `.reset()`.
```

## Workflow

1. Confirm the skill has not expired by checking the Java TTL endpoint.
2. Follow the source request exactly, keeping generated artifacts scoped to this skill.
3. Expose generated backend behavior through Java service/controller classes.
4. Expose generated frontend behavior through the React skill route when a host UI exists.

## Validation

- Verify `SKILL.md` frontmatter includes `created_at`, `ttl_seconds`, and `expires_at`.
- Verify the Java endpoint compiles and serves the skill execution and TTL routes.
- Verify the React route renders the skill and reports expired state cleanly.
- Verify Java TTL garbage collection removes expired generated skill folders.

