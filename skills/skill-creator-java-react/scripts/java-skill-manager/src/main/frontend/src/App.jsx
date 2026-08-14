import { useEffect, useMemo, useState } from "react";

const emptySettings = {
  count: 3,
  direction: "related specializations",
  creativity: 0.65,
  language: "same as original",
  maxDeviation: "moderate",
  themes: "",
  avoidExisting: true,
};

function formatDate(value) {
  if (!value) {
    return "n/a";
  }
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value));
}

function sizeLabel(bytes) {
  if (!Number.isFinite(bytes)) {
    return "n/a";
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  return `${(bytes / 1024).toFixed(1)} KB`;
}

async function readJson(response) {
  const text = await response.text();
  if (!response.ok) {
    let message = text || `HTTP ${response.status}`;
    try {
      message = JSON.parse(text).message || JSON.parse(text).detail || message;
    } catch {
      // Keep raw text.
    }
    throw new Error(message);
  }
  return text ? JSON.parse(text) : null;
}

export default function App() {
  const [originals, setOriginals] = useState([]);
  const [activeOriginalId, setActiveOriginalId] = useState("");
  const [originalDetail, setOriginalDetail] = useState(null);
  const [variations, setVariations] = useState([]);
  const [activeVariation, setActiveVariation] = useState(null);
  const [settings, setSettings] = useState(emptySettings);
  const [files, setFiles] = useState([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [runtime, setRuntime] = useState(null);

  const activeOriginal = useMemo(
    () => originals.find((original) => original.originalId === activeOriginalId) || null,
    [originals, activeOriginalId],
  );
  const canGenerateWithAi = runtime?.llmEnabled === true;
  const runtimeStatusLabel = runtime ? (canGenerateWithAi ? "AI enabled" : "AI disabled") : "AI status unknown";
  const runtimeStatusClass = runtime ? (canGenerateWithAi ? "status-badge" : "status-badge off") : "status-badge muted";

  useEffect(() => {
    refreshRuntime();
    refreshOriginals();
  }, []);

  useEffect(() => {
    if (!activeOriginalId) {
      setOriginalDetail(null);
      setVariations([]);
      setActiveVariation(null);
      return;
    }
    loadOriginal(activeOriginalId);
  }, [activeOriginalId]);

  async function refreshOriginals(nextActiveId) {
    try {
      setError("");
      const list = await readJson(await fetch("/api/v1/original-skills"));
      setOriginals(list);
      const chosen = nextActiveId || activeOriginalId || list[0]?.originalId || "";
      setActiveOriginalId(chosen);
    } catch (err) {
      setError(err.message);
    }
  }

  async function refreshRuntime() {
    try {
      const status = await readJson(await fetch("/api/v1/runtime/status"));
      setRuntime(status);
    } catch {
      setRuntime(null);
    }
  }

  async function loadOriginal(originalId) {
    try {
      setError("");
      const [detail, variationList] = await Promise.all([
        readJson(await fetch(`/api/v1/original-skills/${encodeURIComponent(originalId)}`)),
        readJson(await fetch(`/api/v1/original-skills/${encodeURIComponent(originalId)}/variations`)),
      ]);
      setOriginalDetail(detail);
      setVariations(variationList);
      setActiveVariation(null);
    } catch (err) {
      setError(err.message);
    }
  }

  async function uploadOriginals(event) {
    event.preventDefault();
    const form = event.currentTarget;
    if (!files.length) {
      setError("Select at least one .md file.");
      return;
    }
    const formData = new FormData();
    files.forEach((file) => formData.append("files", file));
    try {
      setBusy(true);
      setError("");
      const uploaded = await readJson(await fetch("/api/v1/original-skills", { method: "POST", body: formData }));
      setFiles([]);
      form.reset();
      await refreshOriginals(uploaded[0]?.originalId);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  async function generateVariations() {
    if (!activeOriginalId) {
      return;
    }
    if (!canGenerateWithAi) {
      setError(runtime ? "AI generation is disabled for this backend session." : "AI status is unavailable; generation is blocked.");
      return;
    }
    try {
      setBusy(true);
      setError("");
      const result = await readJson(
        await fetch(`/api/v1/original-skills/${encodeURIComponent(activeOriginalId)}/variations`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(settings),
        }),
      );
      setVariations((current) => [...result.variations, ...current]);
      setActiveVariation(result.variations[0] || null);
      await refreshOriginals(activeOriginalId);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  async function openVariation(variationId) {
    try {
      setError("");
      const detail = await readJson(
        await fetch(`/api/v1/original-skills/${encodeURIComponent(activeOriginalId)}/variations/${encodeURIComponent(variationId)}`),
      );
      setActiveVariation(detail);
    } catch (err) {
      setError(err.message);
    }
  }

  async function deleteVariation(variationId) {
    try {
      setBusy(true);
      setError("");
      await readJson(
        await fetch(`/api/v1/original-skills/${encodeURIComponent(activeOriginalId)}/variations/${encodeURIComponent(variationId)}`, {
          method: "DELETE",
        }),
      );
      setVariations((current) => current.filter((variation) => variation.variationId !== variationId));
      if (activeVariation?.variationId === variationId) {
        setActiveVariation(null);
      }
      await refreshOriginals(activeOriginalId);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  function downloadUrl(path) {
    return path.split("/").map((part) => encodeURIComponent(part)).join("/");
  }

  return (
    <main className="workspace">
      <header className="app-header">
        <div>
          <p className="eyebrow">SkillCreatorJavaReact</p>
          <h1>Skill Variation Library</h1>
        </div>
        <div className="counts">
          <span>{originals.length} originals</span>
          <span>{variations.length} variations</span>
        </div>
      </header>

      {error && <div className="notice">{error}</div>}

      <section className="layout">
        <aside className="panel library-panel">
          <form className="upload" onSubmit={uploadOriginals}>
            <label>
              <span>Upload Markdown</span>
              <input type="file" accept=".md" multiple onChange={(event) => setFiles(Array.from(event.target.files || []))} />
            </label>
            <button type="submit" disabled={busy || !files.length}>
              Upload
            </button>
          </form>

          <div className="list-header">
            <h2>Originals</h2>
            <button type="button" className="secondary" onClick={() => refreshOriginals()} disabled={busy}>
              Refresh
            </button>
          </div>
          <div className="nav-list">
            {originals.map((original) => (
              <button
                type="button"
                className={original.originalId === activeOriginalId ? "nav-item selected" : "nav-item"}
                key={original.originalId}
                onClick={() => setActiveOriginalId(original.originalId)}
              >
                <strong>{original.title}</strong>
                <span>{original.fileName}</span>
                <small>{original.variationCount} variations</small>
              </button>
            ))}
            {!originals.length && <p className="empty">No originals</p>}
          </div>
        </aside>

        <section className="panel detail-panel">
          <div className="section-head">
            <div>
              <h2>{activeOriginal?.title || "Original"}</h2>
              <p>{activeOriginal ? `${activeOriginal.fileName} - ${sizeLabel(activeOriginal.sizeBytes)} - ${formatDate(activeOriginal.uploadedAt)}` : "No file selected"}</p>
            </div>
            {activeOriginal && (
              <a className="button-link" href={`/api/v1/original-skills/${downloadUrl(activeOriginal.originalId)}/download`}>
                Download
              </a>
            )}
          </div>

          <pre className="markdown-preview">{originalDetail?.content || "Select or upload an original markdown skill."}</pre>

          <div className="generator">
            <div className="generator-head">
              <h2>Generation</h2>
              <span className={runtimeStatusClass}>{runtimeStatusLabel}</span>
            </div>
            <div className="controls">
              <label>
                <span>Count</span>
                <input
                  type="number"
                  min="1"
                  max="12"
                  value={settings.count}
                  onChange={(event) => setSettings({ ...settings, count: Number(event.target.value) })}
                />
              </label>
              <label>
                <span>Creativity</span>
                <input
                  type="range"
                  min="0"
                  max="1"
                  step="0.05"
                  value={settings.creativity}
                  onChange={(event) => setSettings({ ...settings, creativity: Number(event.target.value) })}
                />
              </label>
              <label>
                <span>Deviation</span>
                <select value={settings.maxDeviation} onChange={(event) => setSettings({ ...settings, maxDeviation: event.target.value })}>
                  <option value="low">low</option>
                  <option value="moderate">moderate</option>
                  <option value="high">high</option>
                </select>
              </label>
              <label>
                <span>Language</span>
                <input value={settings.language} onChange={(event) => setSettings({ ...settings, language: event.target.value })} />
              </label>
              <label className="wide">
                <span>Direction</span>
                <input value={settings.direction} onChange={(event) => setSettings({ ...settings, direction: event.target.value })} />
              </label>
              <label className="wide">
                <span>Themes</span>
                <input value={settings.themes} onChange={(event) => setSettings({ ...settings, themes: event.target.value })} />
              </label>
              <label className="check">
                <input
                  type="checkbox"
                  checked={settings.avoidExisting}
                  onChange={(event) => setSettings({ ...settings, avoidExisting: event.target.checked })}
                />
                <span>Avoid existing names</span>
              </label>
            </div>
            <button type="button" disabled={busy || !activeOriginalId || !canGenerateWithAi} onClick={generateVariations}>
              {busy ? "Working" : "Generate"}
            </button>
          </div>
        </section>

        <aside className="panel variations-panel">
          <h2>Variations</h2>
          <div className="variation-list">
            {variations.map((variation) => (
              <div className="variation-row" key={variation.variationId}>
                <button type="button" onClick={() => openVariation(variation.variationId)}>
                  <strong>{variation.name}</strong>
                  <span>{variation.difference}</span>
                </button>
                <div className="row-actions">
                  <a href={`/api/v1/original-skills/${downloadUrl(activeOriginalId)}/variations/${downloadUrl(variation.variationId)}/download`}>Download</a>
                  <button type="button" className="danger" onClick={() => deleteVariation(variation.variationId)} disabled={busy}>
                    Delete
                  </button>
                </div>
              </div>
            ))}
            {!variations.length && <p className="empty">No variations</p>}
          </div>

          <div className="variation-preview">
            <div className="section-head compact">
              <div>
                <h2>{activeVariation?.name || "Preview"}</h2>
                <p>{activeVariation ? `${activeVariation.fileName} - ${formatDate(activeVariation.createdAt)}` : "No variation selected"}</p>
              </div>
            </div>
            <pre className="markdown-preview small">{activeVariation?.content || "Open a generated variation."}</pre>
          </div>
        </aside>
      </section>
    </main>
  );
}
