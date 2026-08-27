import { API_BASE_URL } from "./config";

export class ApiError extends Error {
  status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

interface LoginResponse {
  access_token: string;
  token_type: string;
  expires_in: number;
}

export async function login(username: string, password: string): Promise<LoginResponse> {
  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
    });
  } catch {
    throw new ApiError(0, "Could not reach the WatchWire server. Check your connection and try again.");
  }

  if (!response.ok) {
    if (response.status === 401) {
      throw new ApiError(401, "Incorrect username or password.");
    }
    if (response.status === 429) {
      throw new ApiError(429, "Too many failed attempts. Please wait a few minutes and try again.");
    }
    throw new ApiError(response.status, "Login failed. Please try again.");
  }

  return (await response.json()) as LoginResponse;
}
