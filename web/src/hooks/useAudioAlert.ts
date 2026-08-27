import { useCallback, useRef, useState } from "react";

// Browsers block audio playback until a user gesture unlocks the AudioContext. We build
// the alert tone from oscillators (no binary asset to ship) and expose `unlock()` for a
// button the user can tap once; after that, `playAlert()` works even for events that
// arrive with the tab in the background.
export function useAudioAlert() {
  const audioCtxRef = useRef<AudioContext | null>(null);
  const [isUnlocked, setIsUnlocked] = useState(false);

  const ensureContext = useCallback((): AudioContext | null => {
    if (typeof window === "undefined") return null;
    const AudioContextClass =
      window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!AudioContextClass) return null;
    if (!audioCtxRef.current) {
      audioCtxRef.current = new AudioContextClass();
    }
    return audioCtxRef.current;
  }, []);

  const unlock = useCallback(() => {
    const ctx = ensureContext();
    if (!ctx) return;
    if (ctx.state === "suspended") {
      void ctx.resume().then(() => setIsUnlocked(ctx.state === "running"));
    } else {
      setIsUnlocked(true);
    }
  }, [ensureContext]);

  const playAlert = useCallback(() => {
    const ctx = ensureContext();
    if (!ctx) return;
    if (ctx.state === "suspended") {
      void ctx.resume();
    }

    const now = ctx.currentTime;
    [0, 0.18].forEach((offset, i) => {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = "sine";
      osc.frequency.value = i === 0 ? 880 : 1046.5;
      gain.gain.setValueAtTime(0.0001, now + offset);
      gain.gain.exponentialRampToValueAtTime(0.35, now + offset + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, now + offset + 0.16);
      osc.connect(gain);
      gain.connect(ctx.destination);
      osc.start(now + offset);
      osc.stop(now + offset + 0.2);
    });
  }, [ensureContext]);

  return { playAlert, unlock, isUnlocked };
}
