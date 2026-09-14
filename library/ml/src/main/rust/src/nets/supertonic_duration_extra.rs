/// Hands out `.maml` tensor indices in the order the layers appear.
pub(crate) struct Layers {
    pub(crate) next: usize,
}

impl Layers {
    /// A weight and the bias after it.
    pub(crate) fn take(&mut self) -> usize {
        let index = self.next;
        self.next += 2;
        index
    }

    /// An int8 kernel, its per-output-channel scale, and the bias after that.
    ///
    /// Three rather than two, which is why quantising a convolution shifts every later index. The
    /// order is the one `Builder::conv_int8` reads and `supertonic_fold.py` writes; getting it
    /// wrong puts an fp16 tensor where the kernel should be, and `WeightSource::shaped_words`
    /// refuses that rather than reading it as bytes.
    pub(crate) fn take3(&mut self) -> usize {
        let index = self.next;
        self.next += 3;
        index
    }

    /// A lone tensor: the embedding table, a relative position table, a PReLU slope.
    pub(crate) fn take_one(&mut self) -> usize {
        let index = self.next;
        self.next += 1;
        index
    }
}
