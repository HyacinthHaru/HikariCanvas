// @vitest-environment happy-dom
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { downloadBlob } from '../downloadBlob';

describe('downloadBlob', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('creates an object URL and clicks an anchor with the given filename', () => {
    const clickSpy = vi.fn();
    const createSpy = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:fake');
    const revokeSpy = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
    vi.spyOn(document, 'createElement').mockImplementation(() => {
      return { click: clickSpy, set href(_v: string) {}, set download(_v: string) {} } as unknown as HTMLAnchorElement;
    });

    downloadBlob(new Uint8Array([1, 2, 3]), 'mysign.canvas', 'application/octet-stream');

    expect(createSpy).toHaveBeenCalledOnce();
    expect(clickSpy).toHaveBeenCalledOnce();
    expect(revokeSpy).toHaveBeenCalledOnce();
  });
});
