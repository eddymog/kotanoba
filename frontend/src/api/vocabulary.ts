import { apiFetch } from "./client";
import type { LemmaStatus, OtherVocabularyPage, VocabularyPage, VocabularyStats } from "./types";

export function listVocabulary(page: number, status?: LemmaStatus, pos?: string): Promise<VocabularyPage> {
  const params = new URLSearchParams({ page: String(page) });
  if (status) params.set("status", status);
  if (pos) params.set("pos", pos);
  return apiFetch<VocabularyPage>(`/api/vocabulary?${params}`);
}

export function listOtherVocabulary(page: number, status?: LemmaStatus, pos?: string): Promise<OtherVocabularyPage> {
  const params = new URLSearchParams({ page: String(page) });
  if (status) params.set("status", status);
  if (pos) params.set("pos", pos);
  return apiFetch<OtherVocabularyPage>(`/api/vocabulary/other?${params}`);
}

export function getVocabularyStats(): Promise<VocabularyStats> {
  return apiFetch<VocabularyStats>("/api/vocabulary/stats");
}

export function setVocabularyStatus(term: string, reading: string, status: LemmaStatus): Promise<void> {
  return apiFetch<void>("/api/vocabulary/status", {
    method: "PUT",
    body: JSON.stringify({ term, reading, status }),
  });
}
