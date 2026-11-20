import { useEffect, useMemo, useState } from "react";
import PageHeader from "../../components/ui/PageHeader";
import { adminService, type SettingInfo } from "../../lib/adminService";
import "./admin.css";

// Kiểm tra theo kiểu và khoảng giá trị, khớp SettingDefinitions ở backend.
function validateSetting(s: SettingInfo, raw: string): string | null {
  const v = raw.trim();
  if (s.type === "INT") {
    if (!/^-?\d+$/.test(v)) return "Phải là số nguyên";
    const n = Number(v);
    if (s.min !== null && n < s.min) return `Tối thiểu ${s.min}`;
    if (s.max !== null && n > s.max) return `Tối đa ${s.max}`;
  } else if (s.type === "STRING") {
    if (!v) return "Không được để trống";
    if (v.length > 100) return "Tối đa 100 ký tự";
  }
  return null;
}

export default function SettingsPage() {
  const [settings, setSettings] = useState<SettingInfo[] | null>(null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setLoadError("");
    adminService
      .settings()
      .then((list) => {
        if (!active) return;
        setSettings(list);
        setValues(Object.fromEntries(list.map((s) => [s.key, s.value])));
      })
      .catch((e: Error) => {
        if (active) setLoadError(e.message || "Không thể tải cấu hình.");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [revision]);

  const errors = useMemo(() => {
    const e: Record<string, string> = {};
    for (const s of settings ?? []) {
      const msg = validateSetting(s, values[s.key] ?? "");
      if (msg) e[s.key] = msg;
    }
    return e;
  }, [settings, values]);

  const changedKeys = (settings ?? [])
    .filter((s) => (values[s.key] ?? "").trim() !== s.value)
    .map((s) => s.key);
  const dirty = changedKeys.length > 0;

  const groups = useMemo(() => {
    const map = new Map<string, SettingInfo[]>();
    for (const s of settings ?? []) {
      map.set(s.group, [...(map.get(s.group) ?? []), s]);
    }
    return [...map.entries()];
  }, [settings]);

  useEffect(() => {
    if (!dirty) return;
    const h = (e: BeforeUnloadEvent) => {
      e.preventDefault();
    };
    window.addEventListener("beforeunload", h);
    return () => window.removeEventListener("beforeunload", h);
  }, [dirty]);

  async function save() {
    if (Object.keys(errors).length) {
      setError("Vui lòng sửa các ô đang báo lỗi trước khi lưu.");
      return;
    }
    setSaving(true);
    setMessage("");
    setError("");
    try {
      const payload = Object.fromEntries(
        changedKeys.map((k) => [k, values[k].trim()]),
      );
      const list = await adminService.updateSettings(payload);
      setSettings(list);
      setValues(Object.fromEntries(list.map((s) => [s.key, s.value])));
      setMessage("Đã lưu cấu hình hệ thống.");
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  }

  function resetDefaults() {
    if (!settings) return;
    if (!window.confirm("Đặt tất cả ô về giá trị mặc định? Thay đổi chỉ có hiệu lực sau khi bấm Lưu."))
      return;
    setValues(Object.fromEntries(settings.map((s) => [s.key, s.defaultValue])));
    setMessage("");
    setError("");
  }

  const set = (key: string, value: string) =>
    setValues((v) => ({ ...v, [key]: value }));

  return (
    <>
      <PageHeader
        crumbs={["Trang chủ", "Cấu hình hệ thống"]}
        title="Cấu hình hệ thống"
        subtitle="Các thông số vận hành của hệ thống giao hàng bằng drone."
        code="FR-02"
      />

      {loading && !settings ? (
        <p className="muted admin-state">Đang tải cấu hình...</p>
      ) : loadError ? (
        <div className="card admin-state">
          <p className="field-error">{loadError}</p>
          <button
            type="button"
            className="admin-btn"
            onClick={() => setRevision((n) => n + 1)}
          >
            Thử lại
          </button>
        </div>
      ) : (
        settings && (
          <>
            {message && (
              <div className="alert success" role="status">
                {message}
              </div>
            )}
            {error && (
              <div className="alert error" role="alert">
                {error}
              </div>
            )}
            {dirty && (
              <div className="alert warn" role="status">
                Bạn có thay đổi chưa lưu.
              </div>
            )}

            {groups.map(([group, items]) => (
              <section className="card settings-group" key={group}>
                <h3>{group}</h3>
                {items.map((s) => (
                  <div className="setting-row" key={s.key}>
                    <div className="setting-text">
                      <label htmlFor={`set-${s.key}`}>{s.label}</label>
                      <p className="muted">{s.description}</p>
                      {s.updatedAt && (
                        <small className="muted">
                          Cập nhật {new Date(s.updatedAt).toLocaleString("vi-VN")}
                          {s.updatedBy ? ` bởi ${s.updatedBy}` : ""}
                        </small>
                      )}
                    </div>
                    <div className="setting-input">
                      {s.type === "BOOLEAN" ? (
                        <label className="switch">
                          <input
                            id={`set-${s.key}`}
                            type="checkbox"
                            checked={values[s.key] === "true"}
                            onChange={(e) =>
                              set(s.key, e.target.checked ? "true" : "false")
                            }
                          />
                          <span>{values[s.key] === "true" ? "Bật" : "Tắt"}</span>
                        </label>
                      ) : (
                        <input
                          id={`set-${s.key}`}
                          type={s.type === "INT" ? "number" : "text"}
                          min={s.min ?? undefined}
                          max={s.max ?? undefined}
                          value={values[s.key] ?? ""}
                          aria-invalid={!!errors[s.key]}
                          onChange={(e) => set(s.key, e.target.value)}
                        />
                      )}
                      {errors[s.key] && (
                        <span className="field-error">{errors[s.key]}</span>
                      )}
                      {s.type === "INT" && (
                        <small className="muted">
                          Từ {s.min} đến {s.max} · mặc định {s.defaultValue}
                        </small>
                      )}
                    </div>
                  </div>
                ))}
              </section>
            ))}

            <div className="settings-actions">
              <button
                type="button"
                className="admin-btn"
                onClick={resetDefaults}
                disabled={saving}
              >
                Khôi phục mặc định
              </button>
              <button
                type="button"
                className="btn-dark inline"
                onClick={save}
                disabled={!dirty || saving}
              >
                {saving ? "Đang lưu..." : "Lưu cấu hình"}
              </button>
            </div>
          </>
        )
      )}
    </>
  );
}
