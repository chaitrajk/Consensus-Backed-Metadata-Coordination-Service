import axios from 'axios';

const API_BASE =
  import.meta.env.VITE_API_BASE || window.location.origin;

export const api = axios.create({
  baseURL: API_BASE,
  timeout: 5000,
  headers: { 'Content-Type': 'application/json' },
});

export function getWsUrl(): string {
  const protocol =
    window.location.protocol === 'https:' ? 'wss:' : 'ws:';

  return `${protocol}//${window.location.host}/ws`;
}

