// Minimal WireGuard rekey/keepalive timers - Rust 2021 compat (no let-chains, rand 0.8 compat)

use super::errors::WireGuardError;
use crate::noise::Tunn;
use crate::packet::WgKind;
use std::ops::{Index, IndexMut, RangeInclusive};
use std::time::Duration;
use bytes::BytesMut;
use crate::sleepyinstant::Instant;

pub(crate) const REKEY_AFTER_TIME: Duration = Duration::from_secs(120);
const REJECT_AFTER_TIME: Duration = Duration::from_secs(180);
const REKEY_ATTEMPT_TIME: Duration = Duration::from_secs(90);
pub(crate) const REKEY_TIMEOUT: Duration = Duration::from_secs(5);
const KEEPALIVE_TIMEOUT: Duration = Duration::from_secs(10);
const COOKIE_EXPIRATION_TIME: Duration = Duration::from_secs(120);
pub(crate) const MAX_JITTER: Duration = Duration::from_millis(333);

#[derive(Debug)]
pub enum TimerName {
    TimeCurrent,
    TimeSessionEstablished,
    TimeLastHandshakeStarted,
    TimeLastPacketReceived,
    TimeLastPacketSent,
    TimeLastDataPacketReceived,
    TimeLastDataPacketSent,
    TimeCookieReceived,
    TimePersistentKeepalive,
    Top,
}

use self::TimerName::*;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TimerParams {
    pub keepalive_timeout: RangeInclusive<Duration>,
    pub new_handshake_timeout: RangeInclusive<Duration>,
    pub rekey_timeout: RangeInclusive<Duration>,
    pub rekey_after_time: RangeInclusive<Duration>,
}

impl Default for TimerParams {
    fn default() -> Self {
        TimerParams {
            keepalive_timeout: KEEPALIVE_TIMEOUT..=KEEPALIVE_TIMEOUT,
            new_handshake_timeout: (KEEPALIVE_TIMEOUT + REKEY_TIMEOUT)
                ..=(KEEPALIVE_TIMEOUT + REKEY_TIMEOUT + MAX_JITTER),
            rekey_timeout: REKEY_TIMEOUT..=(REKEY_TIMEOUT + MAX_JITTER),
            rekey_after_time: REKEY_AFTER_TIME..=REKEY_AFTER_TIME,
        }
    }
}

#[derive(Debug)]
pub struct Timers {
    is_initiator: bool,
    time_started: Instant,
    timers: [Duration; TimerName::Top as usize],
    pub(super) session_timers: [Duration; super::N_SESSIONS],
    want_keepalive: Option<Duration>,
    want_handshake: Option<Duration>,
    persistent_keepalive: Option<Duration>,
    persistent_keepalive_due: bool,
    pub(super) params: TimerParams,
    keepalive_timeout: Duration,
    new_handshake_timeout: Duration,
    pub(super) rekey_timeout: Duration,
    rekey_after_time: Duration,
}

impl Timers {
    pub(super) fn new(persistent_keepalive: Option<u16>) -> Timers {
        let persistent_keepalive = persistent_keepalive
            .filter(|&s| s > 0)
            .map(|s| Duration::from_secs(s.into()));
        let mut timers = Timers {
            is_initiator: false,
            time_started: Instant::now(),
            timers: Default::default(),
            session_timers: Default::default(),
            want_keepalive: Default::default(),
            want_handshake: Default::default(),
            persistent_keepalive,
            persistent_keepalive_due: persistent_keepalive.is_some(),
            params: TimerParams::default(),
            keepalive_timeout: Duration::ZERO,
            new_handshake_timeout: Duration::ZERO,
            rekey_timeout: Duration::ZERO,
            rekey_after_time: Duration::ZERO,
        };
        timers.dangerously_set_params(TimerParams::default());
        timers
    }

    pub(super) fn dangerously_set_params(&mut self, params: TimerParams) {
        self.keepalive_timeout = *params.keepalive_timeout.start();
        self.new_handshake_timeout = *params.new_handshake_timeout.start();
        self.rekey_timeout = *params.rekey_timeout.start();
        self.rekey_after_time = *params.rekey_after_time.end();
        self.params = params;
    }

    fn is_initiator(&self) -> bool { self.is_initiator }

    pub(super) fn clear(&mut self) {
        let now = self.now();
        for t in &mut self.timers[..] { *t = now; }
        self.want_handshake = None;
        self.want_keepalive = None;
        self.persistent_keepalive_due = self.persistent_keepalive.is_some();
        self.dangerously_set_params(self.params.clone());
    }

    fn now(&self) -> Duration {
        Instant::now().checked_duration_since(self.time_started).unwrap_or(Duration::ZERO).max(self[TimeCurrent])
    }
}

impl Index<TimerName> for Timers {
    type Output = Duration;
    fn index(&self, index: TimerName) -> &Duration { &self.timers[index as usize] }
}
impl IndexMut<TimerName> for Timers {
    fn index_mut(&mut self, index: TimerName) -> &mut Duration { &mut self.timers[index as usize] }
}

impl<R: rand::RngCore + Send> Tunn<R> {
    pub(super) fn timer_tick(&mut self, timer_name: TimerName) {
        let time = self.timers[TimeCurrent];
        match timer_name {
            TimeLastPacketReceived => {
                self.timers.want_handshake = None;
                self.timers.persistent_keepalive_due = false;
                self.timers[TimePersistentKeepalive] = time;
            }
            TimeLastDataPacketReceived => {
                if self.timers.want_keepalive.is_none() {
                    self.timers.keepalive_timeout = self.sample_timer(|p| &p.keepalive_timeout);
                    self.timers.want_keepalive = Some(time);
                }
            }
            TimeLastPacketSent => {
                self.timers.want_keepalive = None;
                self.timers.persistent_keepalive_due = false;
                self.timers[TimePersistentKeepalive] = time;
            }
            TimeLastDataPacketSent if self.timers.want_handshake.is_none() => {
                self.timers.new_handshake_timeout = self.sample_timer(|p| &p.new_handshake_timeout);
                self.timers.want_handshake = Some(time);
            }
            _ => {}
        }
        self.timers[timer_name] = time;
    }

    pub(super) fn sample_timer(&mut self, range: impl FnOnce(&TimerParams) -> &RangeInclusive<Duration>) -> Duration {
        let range = range(&self.timers.params).clone();
        if range.start() >= range.end() { *range.start() }
        else {
            let span = range.end().as_millis() - range.start().as_millis();
            if span == 0 { return *range.start(); }
            let off = (self.jitter_rng.next_u64() as u128 % (span + 1)) as u64;
            *range.start() + Duration::from_millis(off)
        }
    }

    pub(super) fn timer_tick_session_established(&mut self, is_initiator: bool, session_idx: usize) {
        self.timer_tick(TimeSessionEstablished);
        self.timers.session_timers[session_idx % crate::noise::N_SESSIONS] = self.timers[TimeCurrent];
        self.timers.is_initiator = is_initiator;
        self.timers.rekey_after_time = self.sample_timer(|p| &p.rekey_after_time);
    }

    pub(super) fn clear_all(&mut self) {
        for s in &mut self.sessions { *s = None; }
        self.packet_queue.clear();
        self.timers.clear();
    }

    fn update_session_timers(&mut self, time_now: Duration) {
        for (i, t) in self.timers.session_timers.iter_mut().enumerate() {
            if time_now - *t > REJECT_AFTER_TIME {
                self.sessions[i] = None;
                *t = time_now;
            }
        }
    }

    pub fn update_timers(&mut self) -> Result<Option<WgKind>, WireGuardError> {
        let mut handshake_required = false;
        let mut keepalive_required = false;
        self.rate_limiter.try_reset_count();
        let now = self.timers.now();
        self.timers[TimeCurrent] = now;
        self.update_session_timers(now);

        let session_established = self.timers[TimeSessionEstablished];
        let handshake_started = self.timers[TimeLastHandshakeStarted];
        let data_received = self.timers[TimeLastDataPacketReceived];
        let data_sent = self.timers[TimeLastDataPacketSent];
        let persistent_keepalive = self.timers.persistent_keepalive;

        if self.handshake.is_expired() { return Err(WireGuardError::ConnectionExpired); }

        if self.handshake.has_cookie() && now - self.timers[TimeCookieReceived] >= COOKIE_EXPIRATION_TIME {
            self.handshake.clear_cookie();
        }

        if now - session_established >= REJECT_AFTER_TIME * 3 {
            self.handshake.set_expired();
            self.clear_all();
            return Err(WireGuardError::ConnectionExpired);
        }

        if let Some(time_init_sent) = self.handshake.timer() {
            if now - handshake_started >= REKEY_ATTEMPT_TIME {
                self.handshake.set_expired();
                self.clear_all();
                return Err(WireGuardError::ConnectionExpired);
            }
            if time_init_sent.elapsed() >= self.timers.rekey_timeout {
                handshake_required = true;
            }
        } else {
            if self.timers.is_initiator() {
                if session_established < data_sent && now - session_established >= self.timers.rekey_after_time {
                    handshake_required = true;
                }
                if session_established < data_received
                    && now - session_established >= REJECT_AFTER_TIME - KEEPALIVE_TIMEOUT - REKEY_TIMEOUT
                {
                    handshake_required = true;
                }
            }
            if let Some(since) = self.timers.want_handshake {
                if now.saturating_sub(since) >= self.timers.new_handshake_timeout {
                    handshake_required = true;
                    self.timers.want_handshake = None;
                }
            }
            if !handshake_required {
                if let Some(since) = self.timers.want_keepalive {
                    if now.saturating_sub(since) >= self.timers.keepalive_timeout {
                        keepalive_required = true;
                        self.timers.want_keepalive = None;
                    }
                }
                if let Some(pk) = persistent_keepalive {
                    let due = self.timers.persistent_keepalive_due
                        || now.saturating_sub(self.timers[TimePersistentKeepalive]) >= pk;
                    if due {
                        self.timers.persistent_keepalive_due = false;
                        self.timer_tick(TimePersistentKeepalive);
                        keepalive_required = true;
                    }
                }
            }
        }

        if handshake_required {
            return Ok(self.format_handshake_initiation(true).map(Into::into));
        }
        if keepalive_required {
            return Ok(self.handle_outgoing_packet(crate::packet::Packet::from_bytes(BytesMut::new()), None));
        }
        Ok(None)
    }

    pub fn time_since_last_handshake(&self) -> Option<Duration> {
        let current = self.current;
        if self.sessions[current % super::N_SESSIONS].is_some() {
            Some(self.timers.now().saturating_sub(self.timers[TimeSessionEstablished]))
        } else { None }
    }

    pub fn persistent_keepalive(&self) -> Option<u16> {
        self.timers.persistent_keepalive.map(|d| d.as_secs() as u16).filter(|&v| v > 0)
    }

    pub fn timer_params(&self) -> &TimerParams { &self.timers.params }
    pub fn dangerously_set_timer_params(&mut self, params: TimerParams) {
        self.timers.dangerously_set_params(params);
    }
    pub fn set_persistent_keepalive(&mut self, seconds: Option<u16>) {
        let was = self.timers.persistent_keepalive.is_some();
        self.timers.persistent_keepalive = seconds.filter(|&s| s > 0).map(|s| Duration::from_secs(s.into()));
        self.timers.persistent_keepalive_due = self.timers.persistent_keepalive.is_some()
            && (self.timers.persistent_keepalive_due || !was);
        if self.timers.persistent_keepalive.is_none() {
            self.timers[TimePersistentKeepalive] = Duration::ZERO;
        } else {
            self.timers[TimePersistentKeepalive] = self.timers[TimeCurrent];
        }
    }
}
