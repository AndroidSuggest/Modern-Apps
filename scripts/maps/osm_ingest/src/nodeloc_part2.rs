// The id-index switch and the planet-scale rank-select form.
//
// [`NodeLocations`] holds a [`NodeIds`], which is one of two id indexes chosen by how the ids
// arrived. A vector of needed ids ([`NodeLocations::new`]) builds the compressed [`IdIndex`], whose
// cost follows the id *count* -- right for the sparse needed set of a sub-continent extract. An
// ascending id *stream* ([`NodeLocations::from_sorted`], which the planet path feeds from a bitset
// over the id space) builds a [`RankIndex`], whose cost follows the id *space* and whose `find` is
// `O(1)`. Both answer `find` identically for the same id set -- a position is the count of ids below
// the queried one either way -- so the coordinate array is byte-identical whichever backs it.

/// Which id index backs a [`NodeLocations`]. See the module comment for the choice.
enum NodeIds {
    /// `O(count)` bytes: the compressed block-and-varint index. Built by [`NodeLocations::new`].
    Compressed(IdIndex),
    /// `O(id space)` bytes with `O(1)` lookup: the rank-select bitset. Built by
    /// [`NodeLocations::from_sorted`].
    Rank(RankIndex),
}

impl Default for NodeIds {
    /// An empty compressed index, so [`std::mem::take`] in [`resolve_nodes`] has something to leave
    /// behind. Empty either way, so the variant does not matter.
    fn default() -> Self {
        NodeIds::Compressed(IdIndex::default())
    }
}

impl NodeIds {
    /// From a sorted, unique id vector: the compressed index, for a needed set sparse in the id
    /// space.
    fn build(ids: &[i64]) -> NodeIds {
        NodeIds::Compressed(IdIndex::build(ids))
    }

    /// From an ascending, unique id stream: the rank-select bitset, for a needed set that fills most
    /// of the id space (a planet extract). `len` is unused here -- the bitset sizes itself from the
    /// largest id the stream yields -- but kept to match [`IdIndex::build_sorted`]'s shape.
    fn build_sorted(ids: impl Iterator<Item = i64>, len: usize) -> NodeIds {
        NodeIds::Rank(RankIndex::build_sorted(ids, len))
    }

    fn len(&self) -> usize {
        match self {
            NodeIds::Compressed(index) => index.len(),
            NodeIds::Rank(index) => index.len(),
        }
    }

    fn find(&self, id: i64) -> Option<u64> {
        match self {
            NodeIds::Compressed(index) => index.find(id),
            NodeIds::Rank(index) => index.find(id),
        }
    }

    fn cursor(&self) -> NodeCursor<'_> {
        match self {
            NodeIds::Compressed(index) => NodeCursor::Walk(index.cursor()),
            NodeIds::Rank(index) => NodeCursor::Direct(index),
        }
    }
}

/// A cursor over whichever index backs the table. The compressed side walks (a binary search then a
/// forward scan); the rank side has an `O(1)` `find`, so its "cursor" is just the index and query
/// order is irrelevant to it.
enum NodeCursor<'a> {
    Walk(Cursor<'a>),
    Direct(&'a RankIndex),
}

impl NodeCursor<'_> {
    fn find(&mut self, id: i64) -> Option<u64> {
        match self {
            NodeCursor::Walk(cursor) => cursor.find(id),
            NodeCursor::Direct(index) => index.find(id),
        }
    }
}

/// Words per rank superblock. A superblock stores one prefix-sum `u64`, so this is the overhead/scan
/// trade: 8 words is one `u64` per 512 bits -- ~1.6% over the bitset -- and a `find` pops at most 8
/// words after the superblock lookup.
const RANK_WORDS: usize = 8;

/// A sorted, unique id set as a bitset over the id space plus a rank directory.
///
/// **This, not [`IdIndex`], for a planet extract.** Its size follows the largest id, not the count,
/// so where the needed set is most of a bounded id space it is a few GB against the compressed
/// index's ~12.9 GB -- and `find` is a directory lookup plus a bounded popcount rather than a binary
/// search over a base array that no longer fits in cache.
///
/// `find(id)` returns the id's rank: the number of set bits below it, which is exactly the position
/// [`IdIndex::find`] returns for the same sorted set. That equality is what lets the two indexes back
/// the same coordinate array interchangeably; `the_two_indexes_answer_find_identically` holds them to
/// it.
#[derive(Default)]
struct RankIndex {
    /// Bit `i` is set iff id `i` is in the set.
    words: Vec<u64>,
    /// `ranks[b]` is the number of set bits before superblock `b` (word `b * RANK_WORDS`). A prefix
    /// sum, so a rank is this plus the popcount of the words between the superblock start and the
    /// query word.
    ranks: Vec<u64>,
    len: usize,
}

impl RankIndex {
    /// Build from an ascending, unique, non-negative id stream.
    ///
    /// The bitset grows as ids arrive -- the stream is ascending, so the largest id is the last, and
    /// growth is amortised by the vector's own doubling. The rank directory is filled in one pass
    /// once every bit is set.
    fn build_sorted(ids: impl Iterator<Item = i64>, _len: usize) -> RankIndex {
        let mut words: Vec<u64> = Vec::new();
        let mut count = 0usize;
        for id in ids {
            // The stream's precondition is ascending, unique and non-negative; a negative id has no
            // bit and is dropped rather than allowed to index a wild word.
            if id < 0 {
                continue;
            }
            let bit = id as u64;
            let w = (bit / 64) as usize;
            if w >= words.len() {
                words.resize(w + 1, 0);
            }
            words[w] |= 1u64 << (bit % 64);
            count += 1;
        }
        let mut ranks: Vec<u64> = Vec::with_capacity(words.len() / RANK_WORDS + 1);
        let mut running = 0u64;
        for (i, word) in words.iter().enumerate() {
            if i % RANK_WORDS == 0 {
                ranks.push(running);
            }
            running += word.count_ones() as u64;
        }
        RankIndex { words, ranks, len: count }
    }

    fn len(&self) -> usize {
        self.len
    }

    /// The position of `id` -- the count of set bits below it -- or `None` if it is not in the set.
    fn find(&self, id: i64) -> Option<u64> {
        if id < 0 {
            return None;
        }
        let bit = id as u64;
        let w = (bit / 64) as usize;
        if w >= self.words.len() {
            return None;
        }
        let b = (bit % 64) as u32;
        let word = self.words[w];
        if word & (1u64 << b) == 0 {
            return None;
        }
        let superblock = w / RANK_WORDS;
        let mut rank = self.ranks[superblock];
        for &full in &self.words[superblock * RANK_WORDS..w] {
            rank += full.count_ones() as u64;
        }
        // Bits strictly below `b` in the query word. Zero when `b` is zero, which `1 << 0 - 1` gives.
        rank += (word & ((1u64 << b) - 1)).count_ones() as u64;
        Some(rank)
    }
}

#[cfg(test)]
mod rank_tests {
    use super::*;

    /// **The equality the whole switch rests on.** The rank index and the compressed index must
    /// answer `find` identically for the same sorted set -- present ids to the same position, absent
    /// ids to `None` -- or the planet path would write coordinates at different offsets than the
    /// sub-continent one and the two would not be interchangeable behind [`NodeLocations`].
    #[test]
    fn the_two_indexes_answer_find_identically() {
        let mut state = 0x1234_5678_9abc_def0u64;
        let mut next = || {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            state
        };
        // A mix of tight runs and large gaps, spanning many superblocks, with ids past `u32::MAX`.
        let mut ids: Vec<i64> = Vec::new();
        let mut id = 1i64;
        for i in 0..6_000 {
            id += if i % 5 == 0 { (next() % 5_000) as i64 + 1 } else { (next() % 4) as i64 + 1 };
            ids.push(id);
        }
        ids.push(u32::MAX as i64 + 1);
        ids.push(u32::MAX as i64 + 65);
        ids.sort_unstable();
        ids.dedup();

        let compressed = IdIndex::build(&ids);
        let rank = RankIndex::build_sorted(ids.iter().copied(), ids.len());
        assert_eq!(rank.len(), compressed.len(), "the two indexes disagree on the count");

        for &id in &ids {
            assert_eq!(rank.find(id), compressed.find(id), "present id {id}");
        }
        // Absent ids just below and just above each present one, plus the ends of the space.
        for &id in &ids {
            for probe in [id - 1, id + 1] {
                assert_eq!(rank.find(probe), compressed.find(probe), "probe {probe}");
            }
        }
        for probe in [i64::MIN, -1, 0, *ids.last().unwrap() + 1_000_000, i64::MAX] {
            assert_eq!(rank.find(probe), compressed.find(probe), "outside {probe}");
        }
    }

    /// The rank index is what `from_sorted` builds, so a `NodeLocations` over an ascending stream
    /// must resolve coordinates exactly as one built from the same ids as a vector does.
    #[test]
    fn from_sorted_and_new_resolve_the_same_coordinates() {
        let ids = vec![5i64, 9, 64, 65, 4_000, 4_001, 300_000];
        let mut streamed =
            NodeLocations::from_sorted(ids.iter().copied(), ids.len()).expect("stream");
        let mut vectored = NodeLocations::new(ids.clone()).expect("vector");
        assert_eq!(streamed.len(), vectored.len());
        for &id in &ids {
            // Fill both at the id's own position and read it straight back; the positions must match
            // or the two tables are not interchangeable.
            let a = streamed.index_of(id).expect("present in stream") as usize;
            let b = vectored.index_of(id).expect("present in vector") as usize;
            assert_eq!(a, b, "id {id} sits at a different position in the two indexes");
            streamed.locs.set(a, id as i32, -(id as i32));
            vectored.locs.set(b, id as i32, -(id as i32));
            assert_eq!(streamed.get(id), vectored.get(id), "id {id}");
        }
        assert!(streamed.get(123).is_none(), "an id never in the set must not resolve");
    }

    /// An empty stream and a single-id one, where the superblock arithmetic is easiest to get wrong.
    #[test]
    fn an_empty_or_single_id_rank_index_is_not_a_special_case() {
        let empty = RankIndex::build_sorted(std::iter::empty(), 0);
        assert_eq!(empty.len(), 0);
        assert_eq!(empty.find(0), None);
        assert_eq!(empty.find(42), None);

        let one = RankIndex::build_sorted(std::iter::once(42i64), 1);
        assert_eq!(one.len(), 1);
        assert_eq!(one.find(42), Some(0));
        assert_eq!(one.find(41), None);
        assert_eq!(one.find(43), None);
    }
}
