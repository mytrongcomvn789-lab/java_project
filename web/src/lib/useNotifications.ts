import { useCallback, useEffect, useState } from "react";
import { realtime } from "./realtime";
import {
  notificationService,
  type NotificationItem,
} from "./notificationService";

interface InboxState {
  items: NotificationItem[];
  unreadCount: number;
}

const MAX_ITEMS = 50;

/** Hộp thông báo: tải bằng REST, nhận mới qua socket, tải bù mỗi lần (kết nối lại và) đăng ký kênh xong. */
export function useNotifications(userId: string, enabled = true) {
  const [state, setState] = useState<InboxState>({ items: [], unreadCount: 0 });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(async () => {
    try {
      const page = await notificationService.list({ page: 1, pageSize: 20 });
      setState({ items: page.items, unreadCount: page.unreadCount });
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Không tải được thông báo");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!enabled || !userId) return;
    setLoading(true);
    void reload();
    return realtime.subscribeNotifications(userId, {
      onNotification: (n) =>
        setState((prev) =>
          prev.items.some((x) => x.eventId === n.eventId) // loại trùng theo eventId
            ? prev
            : {
                items: [n, ...prev.items].slice(0, MAX_ITEMS),
                unreadCount: prev.unreadCount + 1,
              },
        ),
      onResync: () => void reload(),
    });
  }, [userId, enabled, reload]);

  const markRead = useCallback(async (id: string) => {
    try {
      await notificationService.markRead(id);
      setState((prev) => {
        const target = prev.items.find((i) => i.id === id);
        if (!target || target.readAt) return prev;
        return {
          items: prev.items.map((i) =>
            i.id === id ? { ...i, readAt: new Date().toISOString() } : i,
          ),
          unreadCount: Math.max(0, prev.unreadCount - 1),
        };
      });
    } catch {
      /* giữ nguyên; lần tải lại sau sẽ đồng bộ với server */
    }
  }, []);

  const markAllRead = useCallback(async () => {
    try {
      await notificationService.markAllRead();
      const now = new Date().toISOString();
      setState((prev) => ({
        items: prev.items.map((i) => (i.readAt ? i : { ...i, readAt: now })),
        unreadCount: 0,
      }));
    } catch {
      /* giữ nguyên */
    }
  }, []);

  return {
    items: state.items,
    unreadCount: state.unreadCount,
    loading,
    error,
    reload,
    markRead,
    markAllRead,
  };
}
