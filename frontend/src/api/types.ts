// Mirrors the backend DTOs directly (com.kotanoba.user / com.kotanoba.text).
// Kept as one file since there's no codegen wired up for this direction yet —
// the backend generates ITS client from FastAPI's OpenAPI schema, but nothing
// generates this frontend's types from the backend's. Worth revisiting if
// these drift.

export type LemmaStatus = "NEW" | "LEARNING" | "KNOWN" | "IGNORED";

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
}

export interface TextDetail {
  id: number;
  title: string;
  createdAt: string;
  tokens: TokenView[];
}
