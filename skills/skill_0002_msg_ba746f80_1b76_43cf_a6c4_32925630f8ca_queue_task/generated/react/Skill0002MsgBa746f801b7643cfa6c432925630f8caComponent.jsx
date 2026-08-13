import React, { useEffect, useState } from "react";

export default function Skill0002MsgBa746f801b7643cfa6c432925630f8caComponent() {
  const [ttl, setTtl] = useState(null);
  const [result, setResult] = useState(null);

  useEffect(() => {
    fetch("/api/v1/skills/skill_0002_msg_ba746f80_1b76_43cf_a6c4_32925630f8ca_queue_task/ttl")
      .then((response) => response.json())
      .then(setTtl)
      .catch(() => setTtl({ expired: true }));
  }, []);

  const execute = async () => {
    const response = await fetch("/api/v1/skills/skill_0002_msg_ba746f80_1b76_43cf_a6c4_32925630f8ca_queue_task/execute", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ input: { sample: "validation" } }),
    });
    setResult(await response.json());
  };

  return (
    <main>
      <h1>Queue Task</h1>
      <p>Skill ID: skill_0002_msg_ba746f80_1b76_43cf_a6c4_32925630f8ca_queue_task</p>
      <p>Status: {ttl?.expired ? "EXPIRED" : "ACTIVE"}</p>
      <button type="button" onClick={execute}>Execute</button>
      {result && <pre>{JSON.stringify(result, null, 2)}</pre>}
    </main>
  );
}
