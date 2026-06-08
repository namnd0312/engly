/** Vocab word create/update request body — mirrors backend VocabWordRequest. */
export interface VocabWordRequest {
  deckId: number;
  term: string;
  meaningVi: string;
  ipa?: string | null;
  exampleEn?: string | null;
  exampleVi?: string | null;
  partOfSpeech?: string | null;
}

/** Admin vocab word response DTO — mirrors backend VocabWordAdminResponse. */
export interface AdminVocabWord {
  id: number;
  deckId: number;
  term: string;
  meaningVi: string;
  ipa: string | null;
  exampleEn: string | null;
  exampleVi: string | null;
  partOfSpeech: string | null;
  verified: boolean;
  createdAt: string;
}

/** Pagination envelope from GET /api/admin/vocab-words. */
export interface VocabWordPageResponse {
  content: AdminVocabWord[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
