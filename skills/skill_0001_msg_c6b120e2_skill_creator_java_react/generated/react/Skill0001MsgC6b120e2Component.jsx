import React, { useEffect, useState } from "react";

export default function Skill0001MsgC6b120e2Component() {
  const [ttl, setTtl] = useState(null);
  const [result, setResult] = useState(null);

  useEffect(() => {
    fetch("/api/v1/skills/skill_0001_msg_c6b120e2_skill_creator_java_react/ttl")
      .then((response) => response.json())
      .then(setTtl)
      .catch(() => setTtl({ expired: true }));
  }, []);

  const execute = async () => {
    const response = await fetch("/api/v1/skills/skill_0001_msg_c6b120e2_skill_creator_java_react/execute", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ input: { sample: "validation" } }),
    });
    setResult(await response.json());
  };

  return (
    <main>
      <h1>Skill Creator Java React</h1>
      <p>Skill ID: skill_0001_msg_c6b120e2_skill_creator_java_react</p>
      <p>Status: {ttl?.expired ? "EXPIRED" : "ACTIVE"}</p>
      <button type="button" onClick={execute}>Execute</button>
      {result && <pre>{JSON.stringify(result, null, 2)}</pre>}
    </main>
  );
}
