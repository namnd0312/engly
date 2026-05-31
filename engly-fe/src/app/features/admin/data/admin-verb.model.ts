/** Admin create/update body — mirrors backend VerbRequest (lengths 100; meaningVi 255). */
export interface VerbRequest {
  baseForm: string;
  pastSimple: string;
  pastParticiple: string;
  pastSimpleAlt: string | null;
  pastParticipleAlt: string | null;
  ipaBase: string | null;
  ipaPast: string | null;
  ipaPp: string | null;
  meaningVi: string;
  verified: boolean;
}

/** Admin verb DTO — mirrors backend VerbAdminResponse (adds numeric id). */
export interface VerbAdminResponse extends VerbRequest {
  id: number;
}

/** Pagination envelope from GET /api/admin/verbs. */
export interface VerbAdminPageResponse {
  content: VerbAdminResponse[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
