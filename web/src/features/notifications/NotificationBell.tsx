import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { realtime, type RealtimeState } from "../../lib/realtime";
import { useNotifications } from "../../lib/useNotifications";
import type { NotificationItem } from "../../lib/notificationService";
import "./notifications.css";

export default function NotificationBell({ userId }: { userId: string }) {
  const { items, unreadCount, loading, error, reload, markRead, markAllRead } =
    useNotifications(userId);
  const [open, setOpen] = useState(false);
  const [link, setLink] = useState<RealtimeState>(realtime.getState());
  const box = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();

  useEffect(() => realtime.onState(setLink), []);

  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => {
      if (!box.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("mousedown", close);
    return () => document.removeEventListener("mousedown", close);
  }, [open]);

  const openItem = (n: NotificationItem) => {
    setOpen(false);
    void markRead(n.id);
    navigate(`/deliveries/${n.orderId}`); // đúng đơn của thông báo
  };

  return (
    <div className="notif" ref={box}>
      <button
        type="button"
        className="icon-btn notif-btn"
        title="Thông báo"
        aria-label={`Thông báo${unreadCount ? `, ${unreadCount} chưa đọc` : ""}`}
        onClick={() => setOpen((v) => !v)}
      >
        🔔
        {unreadCount > 0 && (
          <span className="notif-badge">
            {unreadCount > 99 ? "99+" : unreadCount}
          </span>
        )}
      </button>

      {open && (
        <div
          className="notif-panel"
          role="dialog"
          aria-label="Thông báo đơn hàng"
        >
          <div className="notif-head">
            <strong>Thông báo</strong>
            <button
              type="button"
              className="notif-link"
              disabled={unreadCount === 0}
              onClick={() => void markAllRead()}
            >
              Đánh dấu đã đọc tất cả
            </button>
          </div>
          {link === "reconnecting" && (
            <div className="notif-note">Đang kết nối lại…</div>
          )}
          {error && (
            <div className="notif-note">
              {error}{" "}
              <button
                type="button"
                className="notif-link"
                onClick={() => void reload()}
              >
                Thử lại
              </button>
            </div>
          )}
          {loading && items.length === 0 && (
            <div className="notif-note">Đang tải…</div>
          )}
          {!loading && !error && items.length === 0 && (
            <div className="notif-note">Chưa có thông báo nào.</div>
          )}
          <ul className="notif-list">
            {items.map((n) => (
              <li key={n.id}>
                <button
                  type="button"
                  className={`notif-item${n.readAt ? "" : " unread"}`}
                  onClick={() => openItem(n)}
                >
                  <span className="notif-title">{n.title}</span>
                  <span className="notif-body">{n.body}</span>
                  <span className="notif-time">
                    {new Date(n.createdAt).toLocaleString("vi-VN")}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
