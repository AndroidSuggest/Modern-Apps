//! The [`Flavour`]s a [`super::sentencepiece::Table`] can be parsed with: which ids are
//! reserved and how text is normalised before encoding.
//!
//! Split from `sentencepiece.rs` along the convention line: that module keeps the table
//! parser, the BPE merge loop and decode; this one holds the per-model conventions.
//! Adding a model with new specials or new whitespace handling touches this file and not
//! the codec.

use super::sentencepiece::Flavour;

/// fairseq's layout, which NLLB and SMaLL-100 use.
pub const FAIRSEQ: Flavour = Flavour {
    bos: 0,
    pad: 1,
    eos: 2,
    unk: 3,
    names: ["<s>", "<pad>", "</s>", "<unk>"],
    tidy_whitespace: true,
};

/// Gemma's layout. Note `eos` at 1 and `bos` at 2, the reverse of fairseq's ordering.
pub const GEMMA: Flavour = Flavour {
    bos: 2,
    pad: 0,
    eos: 1,
    unk: 3,
    names: ["<pad>", "<eos>", "<bos>", "<unk>"],
    tidy_whitespace: false,
};

/// T5's layout, which MADLAD uses.
///
/// `<unk>` (id 0) doubles as the decoder start token (`decoder_start_token_id` 0) and the
/// unknown-word id; `<s>` (id 1) is the pad; `</s>` (id 2) is EOS. Id 3 is a real piece
/// (`\n`), NOT a fourth special — the `names` check below asserts only ids 0..2, since
/// T5 has three specials, not four. Unigram scores: log-probabilities for Viterbi, not
/// merge ranks — so `parse` must be told, via this flavour, which encoder to run.
/// See [`Table::encode_unigram`].
///
/// [`Table::encode_unigram`]: super::sentencepiece_unigram::encode_unigram
pub const T5: Flavour = Flavour {
    bos: 0,
    pad: 1,
    eos: 2,
    unk: 0,
    names: ["<unk>", "<s>", "</s>", "\n"],
    tidy_whitespace: false,
};
