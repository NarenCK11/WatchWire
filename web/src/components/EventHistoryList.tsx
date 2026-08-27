import { formatClockTime, scoreLabel } from "../lib/format";
import type { MotionEvent } from "../lib/types";

interface EventHistoryListProps {
  events: MotionEvent[];
}

export function EventHistoryList({ events }: EventHistoryListProps) {
  if (events.length === 0) {
    return <p className="muted">No events recorded yet this session.</p>;
  }

  return (
    <ul className="event-list">
      {events.map((event) => (
        <li key={event.id} className="event-list__item">
          <span className={`event-list__dot event-list__dot--${scoreLabel(event.score).toLowerCase()}`} />
          <span className="event-list__time">{formatClockTime(event.timestamp)}</span>
          <span className="event-list__score">{scoreLabel(event.score)} ({(event.score * 100).toFixed(0)}%)</span>
        </li>
      ))}
    </ul>
  );
}
