//! Search-time working sets: the monotonic radix heap (A* open set) and the
//! two-level page-table scratchpad of per-node A* state.
//!
//! Port of `radix_heap.h` and the `RoutingScratchpad` class in `scratchpad.h`.
//! Both are large, pre-allocated structures reused across routes; `lib.rs`
//! keeps a single instance of each behind a mutex so routes are serialized.

// --- Radix heap ---

#[derive(Clone, Copy)]
struct HeapNode {
    score: u32,
    id: u32,
}

const BUCKET_CAPACITY: usize = 256 * 1024;
const NUM_BUCKETS: usize = 33;

/// Ultra-fast monotonic radix heap with flat, pre-allocated buckets.
pub struct RadixHeap {
    // Flat `NUM_BUCKETS * BUCKET_CAPACITY` array, indexed `bucket * CAP + pos`.
    buckets: Box<[HeapNode]>,
    sizes: [u32; NUM_BUCKETS],
    bucket_mask: u64,
    last_pop_value: u32,
    count: u32,
}

impl RadixHeap {
    pub fn new() -> RadixHeap {
        // Single ~67 MB zeroed allocation (avoids constructing on the stack).
        let buckets = vec![HeapNode { score: 0, id: 0 }; NUM_BUCKETS * BUCKET_CAPACITY]
            .into_boxed_slice();
        RadixHeap {
            buckets,
            sizes: [0; NUM_BUCKETS],
            bucket_mask: 0,
            last_pop_value: 0,
            count: 0,
        }
    }

    #[inline]
    fn get_bucket_idx(&self, score: u32) -> u32 {
        let x = score ^ self.last_pop_value;
        if x == 0 {
            0
        } else {
            32 - x.leading_zeros()
        }
    }

    #[inline]
    pub fn push(&mut self, score: u32, node_id: u32) {
        let i = self.get_bucket_idx(score) as usize;
        let pos = self.sizes[i] as usize;
        self.sizes[i] += 1;
        self.buckets[i * BUCKET_CAPACITY + pos] = HeapNode { score, id: node_id };
        self.bucket_mask |= 1u64 << i;
        self.count += 1;
    }

    #[inline]
    pub fn pop(&mut self) -> u32 {
        if self.sizes[0] == 0 {
            let i = (self.bucket_mask & !1u64).trailing_zeros() as usize;
            let b_size = self.sizes[i] as usize;

            let mut min_score = self.buckets[i * BUCKET_CAPACITY].score;
            for j in 1..b_size {
                let s = self.buckets[i * BUCKET_CAPACITY + j].score;
                if s < min_score {
                    min_score = s;
                }
            }
            self.last_pop_value = min_score;

            for j in 0..b_size {
                let node = self.buckets[i * BUCKET_CAPACITY + j];
                let idx = self.get_bucket_idx(node.score) as usize;
                let dpos = self.sizes[idx] as usize;
                self.sizes[idx] += 1;
                self.buckets[idx * BUCKET_CAPACITY + dpos] = node;
                self.bucket_mask |= 1u64 << idx;
            }

            self.sizes[i] = 0;
            self.bucket_mask &= !(1u64 << i);
        }

        self.sizes[0] -= 1;
        let node_id = self.buckets[self.sizes[0] as usize].id;
        if self.sizes[0] == 0 {
            self.bucket_mask &= !1u64;
        }
        self.count -= 1;
        node_id
    }

    pub fn clear(&mut self) {
        self.sizes = [0; NUM_BUCKETS];
        self.last_pop_value = 0;
        self.count = 0;
        self.bucket_mask = 0;
    }

    #[inline]
    pub fn empty(&self) -> bool {
        self.count == 0
    }
}

// --- Routing scratchpad ---

#[derive(Clone, Copy)]
pub struct Entry {
    pub node_id: u32,
    pub g_fwd: u32,
    pub g_bwd: u32,
    pub p_fwd: u32,
    #[allow(dead_code)]
    pub p_bwd: u32,
    pub last_name_off: u32,
    pub last_type: u8,
}

impl Entry {
    /// Freshly-allocated pages are memset to 0xFF then `last_type` zeroed,
    /// matching the C++ page initializer.
    const FRESH: Entry = Entry {
        node_id: 0xFFFF_FFFF,
        g_fwd: 0xFFFF_FFFF,
        g_bwd: 0xFFFF_FFFF,
        p_fwd: 0xFFFF_FFFF,
        p_bwd: 0xFFFF_FFFF,
        last_name_off: 0xFFFF_FFFF,
        last_type: 0,
    };
}

const PAGE_BITS: u32 = 14;
const ROUTING_PAGE_SIZE: usize = 1 << PAGE_BITS;
const ROUTING_PAGE_MASK: u32 = (ROUTING_PAGE_SIZE as u32) - 1;
const DIR_SIZE: usize = (1usize << 32) >> PAGE_BITS;

/// Two-level page table indexed by `(node_id << 1) | state`, sparsely allocated.
pub struct RoutingScratchpad {
    directory: Vec<Option<Box<[Entry]>>>,
    active_pages: Vec<u32>,
}

impl RoutingScratchpad {
    pub fn new() -> RoutingScratchpad {
        let mut directory = Vec::with_capacity(DIR_SIZE);
        directory.resize_with(DIR_SIZE, || None);
        RoutingScratchpad {
            directory,
            active_pages: Vec::with_capacity(1024),
        }
    }

    pub fn reset(&mut self) {
        for &page_idx in &self.active_pages {
            self.directory[page_idx as usize] = None;
        }
        self.active_pages.clear();
    }

    #[inline]
    pub fn get_entry(&mut self, node_id: u32, state: i32) -> &mut Entry {
        let index = (node_id << 1) | (state as u32 & 1);
        let dir_idx = (index >> PAGE_BITS) as usize;
        let page_offset = (index & ROUTING_PAGE_MASK) as usize;

        if self.directory[dir_idx].is_none() {
            let page = vec![Entry::FRESH; ROUTING_PAGE_SIZE].into_boxed_slice();
            self.directory[dir_idx] = Some(page);
            self.active_pages.push(dir_idx as u32);
        }

        let page = self.directory[dir_idx].as_mut().unwrap();
        let e = &mut page[page_offset];
        if e.node_id == 0xFFFF_FFFF {
            e.node_id = node_id;
        }
        e
    }
}
