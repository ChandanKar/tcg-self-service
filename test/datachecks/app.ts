/**
 * The app side of the V01 checks: sign in to staging as the admin through the UAT guard (which aborts
 * every non-GET except sign-in) and read the API. Use `test` from here, not from @playwright/test.
 */
import { test, expect, signInAdmin, apiJson, apiGet, readState } from '../uat/guard';
export { test, expect };

/** Signs in once per test; afterwards get(path) reads the API with the session. */
export async function session(page: any) {
  await signInAdmin(page);
  return {
    get: <T = any>(url: string) => apiJson<T>(page, url),
    raw: (url: string) => apiGet(page, url),
    /** Every page of a Spring Page endpoint (content + page.totalPages or totalPages). */
    async all<T = any>(url: (page: number) => string, max = 50): Promise<T[]> {
      const out: T[] = [];
      for (let p = 0; p < max; p++) {
        const r: any = await apiJson(page, url(p));
        const content = r.content || [];
        out.push(...content);
        const pages = (r.page && r.page.totalPages) ?? r.totalPages ?? 1;
        if (p + 1 >= pages || !content.length) break;
      }
      return out;
    },
  };
}

/** ISE's id from the acceptance preflight (test/uat/state/preflight.json). */
export const iseId = () => readState<any>('preflight')?.ise?.environmentId as string;
