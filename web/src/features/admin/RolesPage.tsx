import { useEffect, useMemo, useState } from "react";
import PageHeader from "../../components/ui/PageHeader";
import {
  adminService,
  type PermissionInfo,
  type RoleCatalog,
  type RoleInfo,
} from "../../lib/adminService";
import type { RoleName } from "../../types/api";
import "./admin.css";

const sameSet = (a: string[], b: string[]) =>
  a.length === b.length && a.every((x) => b.includes(x));

export default function RolesPage() {
  const [catalog, setCatalog] = useState<RoleCatalog | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [selected, setSelected] = useState<RoleName>("CUSTOMER");
  // Bản nháp quyền theo vai trò (chưa lưu).
  const [draft, setDraft] = useState<Record<string, string[]>>({});
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setLoadError("");
    adminService
      .roles()
      .then((c) => {
        if (!active) return;
        setCatalog(c);
        setDraft(Object.fromEntries(c.roles.map((r) => [r.role, r.permissions])));
      })
      .catch((e: Error) => {
        if (active) setLoadError(e.message || "Không thể tải danh sách vai trò.");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [revision]);

  const role: RoleInfo | undefined = catalog?.roles.find(
    (r) => r.role === selected,
  );
  const current = draft[selected] ?? [];
  const dirty = !!role && !role.locked && !sameSet(current, role.permissions);
  const anyDirty = !!catalog?.roles.some(
    (r) => !r.locked && !sameSet(draft[r.role] ?? [], r.permissions),
  );

  const groups = useMemo(() => {
    const map = new Map<string, PermissionInfo[]>();
    for (const p of catalog?.permissions ?? []) {
      map.set(p.group, [...(map.get(p.group) ?? []), p]);
    }
    return [...map.entries()];
  }, [catalog]);

  // Cảnh báo khi đóng tab với thay đổi chưa lưu.
  useEffect(() => {
    if (!anyDirty) return;
    const h = (e: BeforeUnloadEvent) => {
      e.preventDefault();
    };
    window.addEventListener("beforeunload", h);
    return () => window.removeEventListener("beforeunload", h);
  }, [anyDirty]);

  function selectRole(r: RoleName) {
    if (r === selected) return;
    if (dirty && !window.confirm("Bạn có thay đổi chưa lưu ở vai trò này. Bỏ thay đổi và chuyển vai trò?"))
      return;
    if (dirty && role) setDraft((d) => ({ ...d, [selected]: role.permissions }));
    setSelected(r);
    setMessage("");
    setError("");
  }

  function toggle(code: string) {
    if (!role || role.locked) return;
    setDraft((d) => {
      const cur = d[selected] ?? [];
      return {
        ...d,
        [selected]: cur.includes(code)
          ? cur.filter((c) => c !== code)
          : [...cur, code],
      };
    });
  }

  async function save() {
    if (!role) return;
    setSaving(true);
    setMessage("");
    setError("");
    try {
      const updated = await adminService.updateRolePermissions(
        selected,
        current,
      );
      setCatalog((c) =>
        c
          ? { ...c, roles: c.roles.map((r) => (r.role === selected ? updated : r)) }
          : c,
      );
      setDraft((d) => ({ ...d, [selected]: updated.permissions }));
      setMessage(`Đã lưu quyền của vai trò "${updated.displayName}".`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
      <PageHeader
        crumbs={["Trang chủ", "Vai trò & quyền"]}
        title="Vai trò & quyền"
        subtitle="Chọn một vai trò để xem và chỉnh quyền chức năng của vai trò đó."
        code="FR-02"
      />

      {loading && !catalog ? (
        <p className="muted admin-state">Đang tải vai trò...</p>
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
        catalog && (
          <div className="roles-layout">
            <ul className="card roles-list" aria-label="Danh sách vai trò">
              {catalog.roles.map((r) => {
                const changed =
                  !r.locked && !sameSet(draft[r.role] ?? [], r.permissions);
                return (
                  <li key={r.role}>
                    <button
                      type="button"
                      className={r.role === selected ? "active" : ""}
                      aria-current={r.role === selected}
                      onClick={() => selectRole(r.role)}
                    >
                      <strong>
                        {r.displayName}
                        {changed && <span className="dirty-dot" title="Chưa lưu" />}
                      </strong>
                      <small>
                        {r.userCount} tài khoản
                        {r.isDefault && <span className="tag">Mặc định</span>}
                      </small>
                    </button>
                  </li>
                );
              })}
            </ul>

            {role && (
              <div className="card roles-detail">
                <div className="roles-head">
                  <div>
                    <h3>{role.displayName}</h3>
                    <p className="muted">{role.description}</p>
                  </div>
                  <button
                    type="button"
                    className="btn-dark inline"
                    disabled={!dirty || saving}
                    onClick={save}
                  >
                    {saving ? "Đang lưu..." : "Lưu thay đổi"}
                  </button>
                </div>

                {role.locked && (
                  <div className="alert info" role="note">
                    Vai trò quản trị viên luôn có đầy đủ quyền và được khóa để
                    tránh tự mất quyền quản trị hệ thống.
                  </div>
                )}
                {dirty && (
                  <div className="alert warn" role="status">
                    Bạn có thay đổi chưa lưu.
                  </div>
                )}
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

                {groups.map(([group, perms]) => (
                  <fieldset className="perm-group" key={group}>
                    <legend>{group}</legend>
                    {perms.map((p) => (
                      <label className="perm-item" key={p.code}>
                        <input
                          type="checkbox"
                          checked={current.includes(p.code)}
                          disabled={role.locked || saving}
                          onChange={() => toggle(p.code)}
                        />
                        <span>{p.label}</span>
                      </label>
                    ))}
                  </fieldset>
                ))}
                <p className="muted admin-hint">
                  Lưu ý: hiện các chức năng nghiệp vụ vẫn kiểm tra theo vai trò
                  ở backend; bảng quyền này sẽ được nối dần vào kiểm tra truy
                  cập ở các task sau.
                </p>
              </div>
            )}
          </div>
        )
      )}
    </>
  );
}
