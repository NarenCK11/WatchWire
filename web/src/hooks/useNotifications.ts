import { useCallback, useState } from "react";

type PermissionState = NotificationPermission | "unsupported";

// The in-page alert banner is always shown regardless of this permission -- desktop
// notifications are a bonus channel for when the tab isn't focused, never a requirement.
export function useNotifications() {
  const [permission, setPermission] = useState<PermissionState>(
    typeof Notification !== "undefined" ? Notification.permission : "unsupported",
  );

  const requestPermission = useCallback(async () => {
    if (typeof Notification === "undefined") return;
    try {
      const result = await Notification.requestPermission();
      setPermission(result);
    } catch {
      // Some browsers reject programmatic requests outside a user gesture; the caller's
      // button click satisfies that requirement in practice.
    }
  }, []);

  const notify = useCallback((title: string, body: string) => {
    if (typeof Notification === "undefined" || Notification.permission !== "granted") return;
    if (document.visibilityState === "visible") return;
    try {
      new Notification(title, { body, tag: "watchwire-motion" });
    } catch {
      // Fall back silently to the in-page banner, which is always shown.
    }
  }, []);

  return { permission, requestPermission, notify };
}
