import { apiFetch } from "./client";
import type { LemmaStatus, TextDetail, TextSummary } from "./types";

export function listTexts(): Promise<TextSummary[]> {
  return apiFetch<TextSummary[]>("/api/texts");
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

export function setLemmaStatus(lemmaId: number, status: LemmaStatus): Promise<void> {
  return apiFetch<void>(`/api/lemmas/${lemmaId}/status`, {
    method: "PUT",
    body: JSON.stringify({ status }),
  });
}
