# Device runs (Pixel 8) — OOM avoidance

The Pixel 8 OOM-reboots when memory is overcommitted, and a reboot wipes the
measurement session. The only rule is: never overcommit. Everything below
serves that.

- Memory gate before every heavy run: `adb shell "uptime; free -m"`. No heavy
  run under ~2 GB free. After any reboot (uptime reset): stop, report, do not
  retry blindly.
- Known costs: TEXT upload ~1.1 GB, whole-EMBED ~3.6 GB, standalone head file
  ~402 MB. The crash-safe flow is `check_gemma4_parity --time N` (no logits, no
  head upload, ~1.5 GB peak with the head file).
- The known-killer shape is whole-EMBED upload + logits/head in one process
  (~4.7 GB peak vs ~2.4 GB free). It has rebooted the device three times. Only
  with explicit user approval AND a fresh memory gate.
- One heavy run at a time across all agents — concurrent runs stack toward the
  same OOM.
- Pushes/downloads over 100 MB need approval: multi-GB transfers churn the page
  cache and spike pressure toward the same cliff.
