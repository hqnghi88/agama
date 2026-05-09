export const BASE_URL = 'http://127.0.0.1:8080';
const REQUEST_TIMEOUT = 5000;

interface ApiResponse {
  status: string;
  [key: string]: unknown;
}

interface SimulationStatus {
  running: boolean;
  jobs: Array<{
    id: string;
    state: string;
    progress: number;
    [key: string]: unknown;
  }>;
  uptime: number;
}

class ApiError extends Error {
  constructor(
    public statusCode: number,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

async function request<T>(
  method: string,
  path: string,
  body?: Record<string, unknown>,
): Promise<T> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), REQUEST_TIMEOUT);

  try {
    const response = await fetch(`${BASE_URL}${path}`, {
      method,
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      body: body ? JSON.stringify(body) : undefined,
      signal: controller.signal,
    });

    if (!response.ok) {
      throw new ApiError(
        response.status,
        `HTTP ${response.status}: ${response.statusText}`,
      );
    }

    return (await response.json()) as T;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    if ((error as Error).name === 'AbortError') {
      throw new Error('Request timed out');
    }
    throw new Error(`Network error: ${(error as Error).message}`);
  } finally {
    clearTimeout(timeoutId);
  }
}

export const api = {
  health: () => request<ApiResponse>('GET', '/api/health'),

  getStatus: () => request<SimulationStatus>('GET', '/api/simulation/status'),

  getJobStatus: (jobId: string) =>
    request<ApiResponse>('GET', `/api/simulation/status?job_id=${jobId}`),

  listJobs: () => request<ApiResponse>('GET', '/api/simulation/list'),

  startSimulation: (config?: Record<string, unknown>) =>
    request<ApiResponse>('POST', '/api/simulation/start', config),

  stopSimulation: (jobId?: string) =>
    request<ApiResponse>('POST', '/api/simulation/stop', {job_id: jobId}),

  getFrameUrl: (jobId: string, ts?: number): string =>
    `${BASE_URL}/api/simulation/frame/${jobId}?_=${ts ?? Date.now()}`,
};

export type {ApiResponse, SimulationStatus};
export {BASE_URL, ApiError};
