import {api, BASE_URL} from '../src/services/api';

const originalFetch = global.fetch;

beforeEach(() => {
  global.fetch = jest.fn();
});

afterEach(() => {
  global.fetch = originalFetch;
  jest.restoreAllMocks();
});

describe('API Service', () => {
  describe('health', () => {
    it('calls GET /api/health', async () => {
      const mockResponse = {status: 'ok', uptime: 12345};
      (global.fetch as jest.Mock).mockResolvedValueOnce({
        ok: true,
        json: async () => mockResponse,
      });

      const result = await api.health();
      expect(result).toEqual(mockResponse);
      expect(global.fetch).toHaveBeenCalledWith(
        `${BASE_URL}/api/health`,
        expect.objectContaining({method: 'GET'}),
      );
    });
  });

  describe('getStatus', () => {
    it('calls GET /api/simulation/status', async () => {
      const mockResponse = {status: 'ok', running: false, jobs: [], uptime: 0};
      (global.fetch as jest.Mock).mockResolvedValueOnce({
        ok: true,
        json: async () => mockResponse,
      });

      const result = await api.getStatus();
      expect(result.running).toBe(false);
      expect(result.jobs).toEqual([]);
    });
  });

  describe('startSimulation', () => {
    it('calls POST /api/simulation/start', async () => {
      const mockResponse = {status: 'ok', job_id: 'test-001'};
      (global.fetch as jest.Mock).mockResolvedValueOnce({
        ok: true,
        json: async () => mockResponse,
      });

      const result = await api.startSimulation({steps: 50});
      expect(result.job_id).toBe('test-001');
      expect(global.fetch).toHaveBeenCalledWith(
        `${BASE_URL}/api/simulation/start`,
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({steps: 50}),
        }),
      );
    });
  });

  describe('stopSimulation', () => {
    it('calls POST /api/simulation/stop', async () => {
      const mockResponse = {status: 'ok'};
      (global.fetch as jest.Mock).mockResolvedValueOnce({
        ok: true,
        json: async () => mockResponse,
      });

      const result = await api.stopSimulation('test-001');
      expect(result.status).toBe('ok');
    });
  });

  describe('network error', () => {
    it('throws on network failure', async () => {
      (global.fetch as jest.Mock).mockRejectedValueOnce(
        new Error('Network request failed'),
      );

      await expect(api.health()).rejects.toThrow('Network error');
    });

    it('throws on timeout', async () => {
      (global.fetch as jest.Mock).mockImplementationOnce(
        () =>
          new Promise((_, reject) => {
            setTimeout(
              () => reject(new DOMException('Aborted', 'AbortError')),
              100,
            );
          }),
      );

      await expect(api.health()).rejects.toThrow('timed out');
    });
  });
});
