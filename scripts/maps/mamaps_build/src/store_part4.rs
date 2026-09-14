impl WayReader {
    pub fn open(path: &Path) -> Result<WayReader> {
        let file = File::open(path)
            .map_err(|e| Error(format!("cannot open {}: {e}", path.display())))?;
        Ok(WayReader {
            inner: BufReader::with_capacity(1 << 20, file),
            last_id: 0,
            seen: 0,
        })
    }

    /// The next way's id, class, display name, lane count, per-lane turn masks and carriageway,
    /// with its node refs written into `refs`.
    ///
    /// `refs` belongs to the caller and is cleared here, so one allocation serves the whole file.
    /// Returning a fresh `Vec` instead would be one allocation per classified way, several million
    /// of them, for a buffer whose contents are dead by the next call — the same reasoning as
    /// [`WaySink::record`]. The name and masks are returned owned: only a minority of ways carry
    /// them, so cloning costs nothing next to the geometry.
    ///
    /// A record that ends mid-varint or claims more refs than the file holds is a corrupt spill
    /// rather than a way to skip: this file was written by [`WaySink`] moments ago in the same
    /// process, so anything unreadable means it is not the file we wrote.
    #[allow(clippy::type_complexity)]
    pub fn next(
        &mut self,
        refs: &mut Vec<i64>,
    ) -> Result<
        Option<(
            i64,
            Class,
            Option<String>,
            u8,
            Vec<u16>,
            Vec<u16>,
            Carriageway,
            Option<BuildingAttrs>,
        )>,
    > {
        refs.clear();
        let Some(delta) = self.uvarint_or_end()? else {
            return Ok(None);
        };
        let id = self.last_id.wrapping_add(zigzag(delta));
        self.last_id = id;
        self.seen += 1;
        let class = unpack(self.uvarint()?);
        let count = self.uvarint()?;
        // No `reserve` on `count`: it comes off disk, and a corrupt one would be an allocation
        // request rather than the error this returns for every other kind of damage. The buffer is
        // the caller's and is reused across the whole file, so after the first few records it is
        // already as large as any way needs.
        let mut previous: i64 = 0;
        for _ in 0..count {
            previous = previous.wrapping_add(zigzag(self.uvarint()?));
            refs.push(previous);
        }
        // The name: a byte length, then that many UTF-8 bytes. Zero length is nameless, which is
        // nearly every way; a corrupt length is the same error as a corrupt ref count.
        let name_len = self.uvarint()? as usize;
        let name = if name_len == 0 {
            None
        } else {
            if name_len > 1 << 20 {
                return err(format!(
                    "a ways spill way claims a {name_len}-byte name, which is corruption"
                ));
            }
            let mut bytes = vec![0u8; name_len];
            use std::io::Read;
            self.inner
                .read_exact(&mut bytes)
                .map_err(|e| Error(format!("cannot read the ways spill: {e}")))?;
            Some(
                String::from_utf8(bytes)
                    .map_err(|_| Error("a ways spill way name is not UTF-8".to_string()))?,
            )
        };
        // The lane count, then the two turn-mask lists (forward, backward): a count then each u16.
        let lane_bits = self.uvarint()?;
        let lane_count = u8::try_from(lane_bits)
            .map_err(|_| Error("a ways spill way lane count does not fit a byte".to_string()))?;
        let mut read_masks = || -> Result<Vec<u16>> {
            let n = self.uvarint()? as usize;
            if n > tilecodec::mamaps::body::FEATURE_RECORD_LEN * 1024 {
                return err(format!("a ways spill way claims {n} turn masks, which is corruption"));
            }
            let mut v = Vec::with_capacity(n);
            for _ in 0..n {
                v.push(u16::try_from(self.uvarint()?).map_err(|_| {
                    Error("a ways spill turn mask does not fit a u16".to_string())
                })?);
            }
            Ok(v)
        };
        let turn_fwd = read_masks()?;
        let turn_bwd = read_masks()?;
        let carriageway = unpack_carriageway(self.uvarint()?);
        // The building attributes: a presence byte, then three packed words when set.
        let building = if self.uvarint()? != 0 {
            let a = self.uvarint()?;
            let b = self.uvarint()?;
            let c = self.uvarint()?;
            Some(unpack_building(a, b, c))
        } else {
            None
        };
        Ok(Some((id, class, name, lane_count, turn_fwd, turn_bwd, carriageway, building)))
    }

    /// One varint, or `None` if the file ended cleanly on a record boundary.
    fn uvarint_or_end(&mut self) -> Result<Option<u64>> {
        let mut value: u64 = 0;
        let mut shift: u32 = 0;
        loop {
            let buf = self
                .inner
                .fill_buf()
                .map_err(|e| Error(format!("cannot read the ways spill: {e}")))?;
            if buf.is_empty() {
                if shift == 0 {
                    return Ok(None);
                }
                return err(format!("the ways spill ends mid-varint after {} way(s)", self.seen));
            }
            // Consumed in one go per buffer fill rather than a byte at a time: a varint almost
            // always lies wholly inside the buffer, and there are hundreds of millions of them.
            let mut used = 0usize;
            for &byte in buf {
                used += 1;
                if shift >= 64 {
                    return err("a ways spill varint is longer than 64 bits".to_string());
                }
                value |= ((byte & 0x7f) as u64) << shift;
                shift += 7;
                if byte & 0x80 == 0 {
                    self.inner.consume(used);
                    return Ok(Some(value));
                }
            }
            self.inner.consume(used);
        }
    }

    fn uvarint(&mut self) -> Result<u64> {
        match self.uvarint_or_end()? {
            Some(value) => Ok(value),
            None => err(format!("the ways spill ends mid-record after {} way(s)", self.seen)),
        }
    }
}

fn put_uvarint(out: &mut Vec<u8>, mut value: u64) {
    loop {
        let byte = (value & 0x7f) as u8;
        value >>= 7;
        if value == 0 {
            out.push(byte);
            return;
        }
        out.push(byte | 0x80);
    }
}

/// Zigzagged, so a gap that happens to run backwards costs one bit rather than ten bytes.
/// [`zigzag`] is the decoder, already in `osm_ingest` because the PBF itself is encoded this way.
fn put_svarint(out: &mut Vec<u8>, value: i64) {
    put_uvarint(out, ((value << 1) ^ (value >> 63)) as u64);
}
