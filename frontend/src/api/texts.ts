import { apiFetch } from "./client";
import type { LemmaStatus, TextDetail, TextLibraryPage, TextSortOrder, TextSummary } from "./types";

export function listTexts(page: number, q?: string, sort?: TextSortOrder): Promise<TextLibraryPage> {
  const params = new URLSearchParams({ page: String(page) });
  if (q) params.set("q", q);
  if (sort) params.set("sort", sort);
  return apiFetch<TextLibraryPage>(`/api/texts?${params}`);
}

export function getText(id: number): Promise<TextDetail> {
  return apiFetch<TextDetail>(`/api/texts/${id}`);
}

export function importText(text: string, title?: string): Promise<TextSummary> {
  return apiFetch<TextSummary>("/api/texts", {
    method: "POST",
    body: JSON.stringify({ title: title ?? null, text }),
  });
}

export function deleteText(id: number): Promise<void> {
  return apiFetch<void>(`/api/texts/${id}`, { method: "DELETE" });
}

export function updateTextTitle(id: number, title: string): Promise<void> {
  return apiFetch<void>(`/api/texts/${id}/title`, {
    method: "PUT",
    body: JSON.stringify({ title }),
  });
}

// design.md §15: called once, when leaving the reader — not on every click.
export function saveReadPosition(id: number, position: number): Promise<void> {
  return apiFetch<void>(`/api/texts/${id}/position`, {
    method: "PUT",
    body: JSON.stringify({ position }),
  });
}

export function setLemmaStatus(lemmaId: number, status: LemmaStatus): Promise<void> {
  return apiFetch<void>(`/api/lemmas/${lemmaId}/status`, {
    method: "PUT",
    body: JSON.stringify({ status }),
  });
}
