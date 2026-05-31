/** Mirrors phase-03 VerbResponse DTO exactly (camelCase). */
export interface VerbResponse {
  baseForm: string;
  pastSimple: string;
  pastSimpleAlt: string | null;
  pastParticiple: string;
  pastParticipleAlt: string | null;
  ipaBase: string | null;
  ipaPast: string | null;
  ipaPp: string | null;
  meaningVi: string | null;
  verified: boolean;
}

/** Pagination envelope from GET /api/verbs */
export interface VerbSearchPageResponse {
  content: VerbResponse[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
