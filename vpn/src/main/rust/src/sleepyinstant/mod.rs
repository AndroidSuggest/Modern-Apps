use std::time::Duration;
#[derive(Clone, Copy, Debug)]
pub struct Instant { inner: std::time::Instant }
impl Instant {
    pub fn now() -> Self { Self { inner: std::time::Instant::now() } }
    pub fn checked_duration_since(&self, earlier: Instant) -> Option<Duration> {
        self.inner.checked_duration_since(earlier.inner)
    }
    pub fn duration_since(&self, earlier: Instant) -> Duration {
        self.checked_duration_since(earlier).unwrap_or(Duration::ZERO)
    }
    pub fn elapsed(&self) -> Duration { Self::now().duration_since(*self) }
}
#[cfg(any())] mod unix;
#[cfg(any())] mod windows;
