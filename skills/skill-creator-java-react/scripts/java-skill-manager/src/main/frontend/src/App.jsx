import { useEffect, useMemo, useState } from "react";

const selectedSkillId = decodeURIComponent(window.location.pathname.split("/skills/")[1] || "");

function formatDate(value) {
  if (!value) {
    return "n/a";
  }
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "medium",
  }).format(new Date(value));
}

export default function App() {
  const [skills, setSkills] = useState([]);
  const [ttl, setTtl] = useState(null);
  const [result, setResult] = useState(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  const activeSkill = useMemo(() => {
    if (selectedSkillId) {
      return skills.find((skill) => skill.skillId === selectedSkillId) || { skillId: selectedSkillId };
    }
    return skills[0] || null;
  }, [skills]);

  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        setLoading(true);
        const listResponse = await fetch("/api/v1/skills");
        if (!listResponse.ok) {
          throw new Error(`Skill list failed with ${listResponse.status}`);
        }
        const list = await listResponse.json();
        if (cancelled) {
          return;
        }
        setSkills(list);
        const skillId = selectedSkillId || list[0]?.skillId;
        if (skillId) {
          const ttlResponse = await fetch(`/api/v1/skills/${encodeURIComponent(skillId)}/ttl`);
          if (!ttlResponse.ok) {
            throw new Error(`TTL lookup failed with ${ttlResponse.status}`);
          }
          setTtl(await ttlResponse.json());
        }
      } catch (err) {
        if (!cancelled) {
          setError(err.message);
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    }
    load();
    return () => {
      cancelled = true;
    };
  }, []);

  async function executeSkill() {
    if (!activeSkill?.skillId) {
      return;
    }
    setError("");
    setResult(null);
    const response = await fetch(`/api/v1/skills/${encodeURIComponent(activeSkill.skillId)}/execute`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ input: { sample: "validation", source: "react-ui" } }),
    });
    if (!response.ok) {
      setError(`Execution failed with ${response.status}`);
      return;
    }
    setResult(await response.json());
  }

  return (
    <main className="shell">
      <section className="topbar">
        <div>
          <p className="eyebrow">SkillCreatorJavaReact</p>
          <h1>{activeSkill?.skillName || activeSkill?.skillId || "No active skill"}</h1>
        </div>
        <div className={ttl?.expired ? "status expired" : "status active"}>
          {ttl?.expired ? "Expired" : "Active"}
        </div>
      </section>

      {error && <div className="notice">{error}</div>}

      <section className="grid">
        <article className="panel">
          <dl>
            <div>
              <dt>Skill ID</dt>
              <dd>{activeSkill?.skillId || "n/a"}</dd>
            </div>
            <div>
              <dt>Message ID</dt>
              <dd>{activeSkill?.messageId || "n/a"}</dd>
            </div>
            <div>
              <dt>Sequence</dt>
              <dd>{activeSkill?.seqNumber || "n/a"}</dd>
            </div>
            <div>
              <dt>TTL</dt>
              <dd>{ttl ? `${ttl.remainingSeconds}s remaining` : loading ? "Loading" : "n/a"}</dd>
            </div>
            <div>
              <dt>Created</dt>
              <dd>{formatDate(ttl?.createdAt || activeSkill?.createdAt)}</dd>
            </div>
            <div>
              <dt>Expires</dt>
              <dd>{formatDate(ttl?.expiresAt || activeSkill?.expiresAt)}</dd>
            </div>
          </dl>
          <button type="button" disabled={!activeSkill?.skillId} onClick={executeSkill}>
            Execute
          </button>
        </article>

        <article className="panel">
          <h2>Result</h2>
          <pre>{result ? JSON.stringify(result, null, 2) : "Awaiting execution"}</pre>
        </article>
      </section>
    </main>
  );
}
