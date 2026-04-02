export interface User {
  id: string;
  username: string;
  name?: string;
  nome?: string;
  email: string;
  foto_url?: string;
  role?: string;
}

export interface LoginResponse {
  ok: boolean;
  token: string;
  user: User;
  role: string;
}

export interface WhoAmIResponse {
  ok: boolean;
  user: User;
  role: string;
  exp: number;
}

export interface JwtPayload {
  sub: string;
  perfil: string;
  username: string;
  nome: string;
  email: string;
  sid: number;
  iat: number;
  exp: number;
}

export interface PaginationMeta {
  page: number;
  limit: number;
  total: number;
  pages: number;
  distinct?: Record<string, { value: string; label: string }[]>;
}

export interface PagedResponse<T = Record<string, unknown>> {
  ok: boolean;
  data: T[];
  meta: PaginationMeta;
}

export interface ApiResponse<T = unknown> {
  ok: boolean;
  data?: T;
  error?: string;
  message?: string;
}
