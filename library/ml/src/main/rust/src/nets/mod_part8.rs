
impl<'a> Builder<'a> {
    /// For each tensor, the last step that reads it, or `None` if nothing does.
    fn last_use(&self) -> Vec<Option<usize>> {
        let mut last = vec![None; self.shapes.len()];
        for (step, node) in self.nodes.iter().enumerate() {
            for Id(id) in node.inputs() {
                if let Some(slot) = last.get_mut(id) {
                    *slot = Some(step);
                }
            }
        }
        last
    }

    /// The arena offset of a folded addend, or [`NO_FUSE`] when there is none.
    ///
    /// `None` is the common case — only [`Builder::finish`]'s fusion fold sets these — and it
    /// must resolve without touching the allocator, since "no addend" is not "the tensor at 0".
    fn fuse_offset(
        id: Option<Id>,
        at: &dyn Fn(Id) -> Result<u32, String>,
    ) -> Result<u32, String> {
        match id {
            Some(id) => at(id),
            None => Ok(NO_FUSE),
        }
    }
}
