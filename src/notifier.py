"""
Multi-Channel Smart Notifications (ntfy / Home Assistant / Webhook)
===================================================================
Allows Nozzle Guardian to push alerts directly to active iOS/Android
smartphones (via ntfy or webhook) OR into Home Assistant — zero cloud lock-in.
"""

import logging
from typing import Optional
import httpx

from .config import Settings

logger = logging.getLogger("nozzle_guardian.notifier")


class Notifier:
    def __init__(self, cfg: Optional[Settings] = None):
        self.cfg = cfg

    async def send_alert(self, title: str, message: str, event_type: str = "purge") -> bool:
        from .config import settings as global_settings
        cfg = self.cfg or global_settings
        notif = getattr(cfg, "notifications", None)
        if not notif:
            return False

        sent = False
        async with httpx.AsyncClient(timeout=8.0, verify=True) as client:
            # 1. ntfy.sh / self-hosted ntfy push notification (iOS & Android native app)
            if notif.ntfy_url:
                try:
                    headers = {
                        "Title": title[:120],
                        "Priority": "high" if event_type in ("error", "media-empty") else "default",
                        "Tags": "printer" if event_type == "purge" else "warning",
                    }
                    if notif.dashboard_url:
                        headers["Actions"] = f"view, Apri Nozzle Guardian, {notif.dashboard_url}"
                    resp = await client.post(notif.ntfy_url.rstrip("/"), content=message.encode("utf-8"), headers=headers)
                    if resp.status_code < 300:
                        sent = True
                except Exception as exc:
                    logger.warning(f"ntfy notification failed: {exc}")

            # 2. Home Assistant Supervisor / Long-Lived Token REST API
            if notif.ha_url and notif.ha_token:
                ha_headers = {
                    "Authorization": f"Bearer {notif.ha_token}",
                    "Content-Type": "application/json",
                }
                base = notif.ha_url.rstrip("/")
                svc = (notif.ha_notify_service or "notify").replace("notify.", "")
                try:
                    resp = await client.post(
                        f"{base}/api/services/notify/{svc}",
                        headers=ha_headers,
                        json={"title": title, "message": message},
                    )
                    if resp.status_code < 300:
                        sent = True
                except Exception as exc:
                    logger.debug(f"Home Assistant notify/{svc} failed: {exc}")

                try:
                    await client.post(
                        f"{base}/api/services/persistent_notification/create",
                        headers=ha_headers,
                        json={
                            "notification_id": f"nozzle_guardian_{event_type}",
                            "title": title,
                            "message": message,
                        },
                    )
                except Exception:
                    pass

            # 3. Generic JSON webhook (Telegram relay / Discord / custom automation)
            if notif.webhook_url:
                try:
                    resp = await client.post(
                        notif.webhook_url,
                        json={
                            "source": "nozzle-guardian",
                            "event_type": event_type,
                            "title": title,
                            "message": message,
                        },
                    )
                    if resp.status_code < 300:
                        sent = True
                except Exception as exc:
                    logger.warning(f"Webhook notification failed: {exc}")

        return sent
