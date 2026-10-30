//! Puts frames that arrive over several links (USB + Wi-Fi) back into encoder order.
use std::collections::BTreeMap;
use std::time::{Duration, Instant};

pub const CONFIG: u8 = 1;
pub const KEY: u8 = 2;

/// Gap older than this, or this many frames waiting: give up on the missing frame.
const MAX_WAIT: Duration = Duration::from_millis(150);
const MAX_HELD: usize = 90;

#[derive(Default)]
pub struct Merger {
    next: u32,
    started: bool,
    waiting_key: bool,
    held: BTreeMap<u32, (u8, Vec<u8>)>,
    gap_since: Option<Instant>,
    pub dropped: u64,
}

pub struct Out {
    pub frames: Vec<(u8, Vec<u8>)>,
    /// Ask the phone for a key frame (a frame was lost).
    pub need_key: bool,
}

impl Merger {
    pub fn push(&mut self, seq: u32, ty: u8, data: Vec<u8>, now: Instant) -> Out {
        let mut out = Out { frames: vec![], need_key: false };
        let sync = ty == CONFIG || ty == KEY;
        if !self.started || self.waiting_key {
            if !sync {
                self.dropped += 1;
                return out;
            }
            self.started = true;
            self.waiting_key = false;
            self.next = seq;
            self.held.retain(|&s, _| s > seq);
        }
        if seq.wrapping_sub(self.next) > u32::MAX / 2 {
            return out; // older than what we already delivered: duplicate or too late
        }
        self.held.insert(seq, (ty, data));
        self.drain(&mut out);
        if self.held.is_empty() {
            self.gap_since = None;
            return out;
        }
        let since = *self.gap_since.get_or_insert(now);
        if now.duration_since(since) > MAX_WAIT || self.held.len() > MAX_HELD {
            // Skip the hole: continue at the next sync frame, or wait for one.
            match self.held.iter().find(|(_, (t, _))| *t == CONFIG || *t == KEY).map(|(s, _)| *s) {
                Some(s) => {
                    let before = self.held.range(..s).count() as u64;
                    self.dropped += before;
                    self.held.retain(|&k, _| k >= s);
                    self.next = s;
                    self.drain(&mut out);
                }
                None => {
                    self.dropped += self.held.len() as u64;
                    self.held.clear();
                    self.waiting_key = true;
                }
            }
            out.need_key = true;
            self.gap_since = if self.held.is_empty() { None } else { Some(now) };
        }
        out
    }

    /// The decoder fell behind: drop everything until the next key frame.
    pub fn resync(&mut self) {
        self.dropped += self.held.len() as u64;
        self.held.clear();
        self.waiting_key = true;
        self.gap_since = None;
    }

    fn drain(&mut self, out: &mut Out) {
        while let Some(f) = self.held.remove(&self.next) {
            out.frames.push(f);
            self.next = self.next.wrapping_add(1);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn seqs(o: &Out) -> Vec<u8> {
        o.frames.iter().map(|f| f.1[0]).collect()
    }

    #[test]
    fn reorders_two_links() {
        let mut m = Merger::default();
        let t = Instant::now();
        assert_eq!(seqs(&m.push(0, KEY, vec![0], t)), [0]);
        assert!(seqs(&m.push(2, 3, vec![2], t)).is_empty());
        assert_eq!(seqs(&m.push(1, 3, vec![1], t)), [1, 2]);
        assert!(seqs(&m.push(1, 3, vec![1], t)).is_empty()); // duplicate
    }

    #[test]
    fn waits_for_first_key_frame() {
        let mut m = Merger::default();
        let t = Instant::now();
        assert!(m.push(5, 3, vec![5], t).frames.is_empty());
        assert_eq!(seqs(&m.push(6, KEY, vec![6], t)), [6]);
    }

    #[test]
    fn skips_lost_frame_at_next_key() {
        let mut m = Merger::default();
        let t = Instant::now();
        m.push(0, KEY, vec![0], t);
        m.push(2, 3, vec![2], t); // 1 is lost
        m.push(3, KEY, vec![3], t);
        let o = m.push(4, 3, vec![4], t + Duration::from_millis(200));
        assert_eq!(seqs(&o), [3, 4]);
        assert!(o.need_key);
        assert_eq!(m.dropped, 1);
    }

    #[test]
    fn lost_frame_without_key_waits_for_one() {
        let mut m = Merger::default();
        let t = Instant::now();
        m.push(0, KEY, vec![0], t);
        m.push(2, 3, vec![2], t);
        let o = m.push(3, 3, vec![3], t + Duration::from_millis(200));
        assert!(o.frames.is_empty() && o.need_key);
        assert!(m.push(4, 3, vec![4], t).frames.is_empty());
        assert_eq!(seqs(&m.push(5, KEY, vec![5], t)), [5]);
    }
}
