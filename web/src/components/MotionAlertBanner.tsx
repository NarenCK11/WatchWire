import { useEffect, useState } from "react";
import { formatClockTime, formatSecondsAgo, scoreLabel } from "../lib/format";
import type { MotionEvent } from "../lib/types";

const FRESH_WINDOW_MS = 8000;

interface MotionAlertBannerProps {
  event: MotionEvent | null;
}

export function MotionAlertBanner({ event }: MotionAlertBannerProps) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (!event) return;
    const interval = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(interval);
  }, [event]);

  if (!event) {
    return (
      <div className="alert-banner alert-banner--idle">
        <span className="alert-banner__icon">👁️</span>
        <div>
          <div className="alert-banner__title">No motion detected yet</div>
          <div className="alert-banner__subtitle">You'll see a large alert here the moment motion is detected.</div>
        </div>
      </div>
    );
  }

  const age = now - event.receivedAt;
  const isFresh = age < FRESH_WINDOW_MS;

  return (
    <div className={`alert-banner ${isFresh ? "alert-banner--active" : "alert-banner--recent"}`} role="alert">
      <span className="alert-banner__icon">{isFresh ? "🚨" : "🕓"}</span>
      <div>
        <div className="alert-banner__title">{isFresh ? "Motion Detected!" : "Last motion event"}</div>
        <div className="alert-banner__subtitle">
          {scoreLabel(event.score)} confidence ({(event.score * 100).toFixed(0)}%) &middot; {formatClockTime(event.timestamp)} &middot;{" "}
          {formatSecondsAgo(age)}
        </div>
      </div>
    </div>
  );
}
