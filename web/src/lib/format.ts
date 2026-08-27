export function formatClockTime(isoTimestamp: string): string {
  const date = new Date(isoTimestamp);
  if (Number.isNaN(date.getTime())) return isoTimestamp;
  return date.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

export function formatSecondsAgo(millisAgo: number): string {
  const seconds = Math.max(0, Math.floor(millisAgo / 1000));
  if (seconds < 5) return "just now";
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  return `${hours}h ago`;
}

export function scoreLabel(score: number): string {
  if (score >= 0.75) return "High";
  if (score >= 0.4) return "Medium";
  return "Low";
}
