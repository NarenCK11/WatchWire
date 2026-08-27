interface StatusBadgeProps {
  label: string;
  active: boolean;
  activeText: string;
  inactiveText: string;
  pulse?: boolean;
}

export function StatusBadge({ label, active, activeText, inactiveText, pulse = false }: StatusBadgeProps) {
  return (
    <div className={`status-badge ${active ? "status-badge--active" : "status-badge--inactive"}`}>
      <span className={`status-dot ${pulse && active ? "status-dot--pulse" : ""}`} />
      <div>
        <div className="status-badge__label">{label}</div>
        <div className="status-badge__value">{active ? activeText : inactiveText}</div>
      </div>
    </div>
  );
}
