// Mirrors the backend DTOs directly (com.kotanoba.user / com.kotanoba.text).
// Kept as one file since there's no codegen wired up for this direction yet —
// the backend generates ITS client from FastAPI's OpenAPI schema, but nothing
// generates this frontend's types from the backend's. Worth revisiting if
// these drift.

export type LemmaStatus = "NEW" | "LEARNING" | "KNOWN" | "IGNORED";
export type TextSortOrder = "DIFFICULTY" | "RECENT";

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresInSeconds: number;
}

export interface TextSummary {
  id: number;
  title: string;
  tokenCount: number;
  distinctLemmaCount: number;
  createdAt: string;
  // null until the text is opened at least once (design.md §14) — never set
  // by import itself.
  lastOpenedAt: string | null;
  // null only on the import response — the library list always has a real,
  // computed score (backend: TextSummaryResponse.from vs TextLibraryRepository).
  difficultyScore: number | null;
}

export interface TextLibraryPage {
  page: number;
  totalPages: number;
  texts: TextSummary[];
}

export interface TokenView {
  position: number;
  charStart: number;
  charEnd: number;
  surfaceText: string;
  reading: string | null;
  lemmaId: number | null;
  isWord: boolean;
  status: LemmaStatus | null;
  senses: string[] | null;
  partOfSpeech: string | null;
  exampleJapanese: string | null;
  exampleEnglish: string | null;
}

export interface TextDetail {
  id: number;
  title: string;
  createdAt: string;
  // null until the reader has saved a position at least once (design.md §15).
  lastReadPosition: number | null;
  tokens: TokenView[];
}

export interface VocabularyWord {
  term: string;
  reading: string;
  rank: number;
  status: LemmaStatus;
  partOfSpeech: string | null;
  senses: string[] | null;
  exampleJapanese: string | null;
  exampleEnglish: string | null;
}

export interface VocabularyPage {
  page: number;
  totalPages: number;
  words: VocabularyWord[];
}

// A word you've actually encountered (a real lemma, from a real import) that
// falls outside the top 10k frequency list (design.md §17) — no rank (doesn't
// apply). senses/example come from dictionary_entry/word_example (§18), not
// word_frequency, which has no row for these words by definition.
export interface OtherVocabularyWord {
  lemmaId: number;
  term: string;
  reading: string;
  status: LemmaStatus;
  partOfSpeech: string | null;
  senses: string[] | null;
  exampleJapanese: string | null;
  exampleEnglish: string | null;
}

export interface OtherVocabularyPage {
  page: number;
  totalPages: number;
  words: OtherVocabularyWord[];
}

// design.md §19: NEW/LEARNING/KNOWN/IGNORED counts for one category of
// words. total is newCount + learningCount + knownCount + ignoredCount.
export interface StatusCounts {
  total: number;
  newCount: number;
  learningCount: number;
  knownCount: number;
  ignoredCount: number;
}

export interface VocabularyStats {
  topWords: StatusCounts;
  otherWords: StatusCounts;
}
