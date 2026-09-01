// English labels for Sudachi's top-level POS categories. Trimmed to the 13
// categories that actually occur in the seeded top-10k word_frequency data
// (verified against the real table — punctuation/symbol/filler/whitespace
// categories exist in Sudachi's tagset generally, but never appear here
// since this list is real vocabulary, not raw tokenized text).

const LABELS: Record<string, string> = {
  名詞: "Noun",
  代名詞: "Pronoun",
  動詞: "Verb",
  形容詞: "Adjective",
  形状詞: "Na-adjective",
  副詞: "Adverb",
  連体詞: "Attributive",
  接続詞: "Conjunction",
  感動詞: "Interjection",
  助詞: "Particle",
  助動詞: "Auxiliary verb",
  接頭辞: "Prefix",
  接尾辞: "Suffix",
};

// For the POS filter dropdown — value is the raw category the backend
// matches against pos_categories (V11), label is what's shown.
export const POS_CATEGORY_OPTIONS: { value: string; label: string }[] = Object.entries(LABELS).map(
  ([value, label]) => ({ value, label })
);

// For display in the status picker. raw is either a full comma-joined tag
// for a single word ("動詞,一般,*,*,...") or, for multi-morpheme
// frequency-list entries jpdb counts as one unit (には, でもない), each
// constituent token's top-level category joined with "+" (backend: V8's
// migration comment).
export function partOfSpeechLabel(raw: string | null): string | null {
  if (!raw) return null;
  return raw
    .split("+")
    .map((part) => {
      const topLevel = part.split(",")[0];
      return LABELS[topLevel] ?? topLevel;
    })
    .join(" + ");
}
