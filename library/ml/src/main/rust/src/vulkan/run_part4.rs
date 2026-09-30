impl Net {
    /// Per-dispatch device times in microseconds, in plan order, plus the
    /// gap after each op.
    ///
    /// Reads back the timestamp pairs `record` bracketed every dispatch with,
    /// when [`timestamps_enabled`] held at record time. Each entry names the
    /// op (`"{step}: {Kind:?}"`), its COMPUTE_SHADER-stage delta converted by
    /// `timestampPeriod`, and the gap to the next dispatch's start stamp —
    /// which is the barrier (plus bind/push/dispatch issue) between them.
    /// Entries cover dispatch ops only — copies take no queries.
    ///
    /// Call after an inference returns: the fence wait guarantees the results
    /// are available, and `WAIT` covers the gap anyway. Returns an error when
    /// no timestamps were recorded rather than rows of zeros that would read
    /// as impossibly fast dispatches.
    pub fn op_times(&self) -> Result<Vec<(String, f64, f64)>, String> {
        if self.query_pool == vk::QueryPool::null() {
            return Err("no timestamps recorded: set MODELRUNNER_TIMESTAMPS=1".into());
        }
        let dispatches = self
            .plan
            .ops
            .iter()
            .filter(|op| matches!(op, Op::Dispatch { .. }))
            .count();
        if dispatches == 0 {
            return Ok(Vec::new());
        }
        let mut raw = vec![0u64; dispatches * 2];
        // SAFETY: the pool was created for exactly this many queries, the last
        // submit's fence was waited on before `infer` returned, and `WAIT`
        // covers any remaining gap. ash derives the count and stride from the
        // slice: one u64 per query.
        unsafe {
            self.context
                .device
                .get_query_pool_results(
                    self.query_pool,
                    0,
                    &mut raw,
                    vk::QueryResultFlags::TYPE_64 | vk::QueryResultFlags::WAIT,
                )
                .map_err(|e| format!("get_query_pool_results {e:?}"))?;
        }
        let period = f64::from(self.context.limits.timestamp_period);
        // Raw starts and ends per dispatch, to attribute the gaps between
        // them (barriers + bind/push/issue) as well as the deltas.
        let mut stamps: Vec<(u64, u64)> = Vec::with_capacity(dispatches);
        for pair in 0..dispatches {
            match (raw.get(2 * pair), raw.get(2 * pair + 1)) {
                (Some(&b), Some(&a)) => stamps.push((b, a)),
                _ => return Err(format!("timestamp pair {pair} missing")),
            }
        }
        let mut out = Vec::with_capacity(dispatches);
        let mut dispatch = 0usize;
        for (step, op) in self.plan.ops.iter().enumerate() {
            let Op::Dispatch { kind, .. } = op else { continue };
            let (before, after) = stamps[dispatch];
            // Wrapping subtraction: the counter is only `timestampValidBits`
            // wide and wraps within a long submit.
            let ticks = after.wrapping_sub(before);
            let gap = if dispatch + 1 < dispatches {
                stamps[dispatch + 1].0.wrapping_sub(after)
            } else {
                0
            };
            dispatch += 1;
            out.push((
                format!("{step}: {kind:?}"),
                ticks as f64 * period / 1000.0,
                gap as f64 * period / 1000.0,
            ));
        }
        Ok(out)
    }
}
