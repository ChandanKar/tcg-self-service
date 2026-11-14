import { DescribeRegionsCommand } from '@aws-sdk/client-ec2';
import { test, session } from './app';
import { ec2, readOnly, whoAmI, defaultRegion } from './aws';
import { check, evidence } from './record';

/** H0: the harness can read the app (GET) and AWS (read-only), and refuses a write. */
test('H0 harness smoke', async ({ page }) => {
  const app = await session(page);
  const regions = await app.get<string[]>('/api/v1/environments/regions');
  const me = await whoAmI();
  const r: any = await readOnly(ec2(), new DescribeRegionsCommand({}));
  let refused = false;
  try { await readOnly(ec2(), { constructor: { name: 'StopInstancesCommand' } }); } catch (e: any) { refused = /refused/.test(e.message); }
  check({ id: 'H0', title: 'Harness reads the app and AWS read-only', source: 'staging API, AWS STS/EC2', method: 'GET regions; GetCallerIdentity; DescribeRegions; a write command must be refused',
    sample: 'one call each', expected: 'all succeed; write refused', actual: `app regions ${regions.length}; AWS account ${me.account.slice(-4)}; ${r.Regions.length} AWS regions; write refused ${refused}; default region ${defaultRegion()}`,
    status: regions.length && r.Regions.length && refused ? 'PASS' : 'FAIL', evidence: evidence('H0', { appRegions: regions, awsRegions: r.Regions.length, refused }) });
});
