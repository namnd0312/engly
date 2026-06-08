/** Deck create/update request body — mirrors backend DeckRequest. */
export interface DeckRequest {
  name: string;
  description?: string | null;
  topic?: string | null;
}

/** Admin deck response DTO — mirrors backend DeckAdminResponse. */
export interface AdminDeck {
  id: number;
  name: string;
  slug: string;
  description: string | null;
  topic: string | null;
  createdAt: string;
}

/** Pagination envelope from GET /api/admin/decks. */
export interface DeckPageResponse {
  content: AdminDeck[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
