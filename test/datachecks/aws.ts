/**
 * Read-only AWS access for the V01 data checks. Clients use the repository .env keys
 * (AWS_ACCESS_KEY / AWS_SECRET_KEY / AWS_REGION). readOnly() refuses any command that is not
 * Describe*, List*, Get* or Lookup*, so a check can never change AWS. Denied calls surface as
 * AwsDenied, which checks record as BLOCKED with the missing action.
 */
import { EC2Client } from '@aws-sdk/client-ec2';
import { CloudWatchClient } from '@aws-sdk/client-cloudwatch';
import { CloudTrailClient } from '@aws-sdk/client-cloudtrail';
import { CostExplorerClient } from '@aws-sdk/client-cost-explorer';
import { STSClient, GetCallerIdentityCommand } from '@aws-sdk/client-sts';

const READ_ONLY = /^(Describe|List|Get|Lookup)[A-Za-z]*Command$/;

function credentials() {
  const accessKeyId = process.env.AWS_ACCESS_KEY || '';
  const secretAccessKey = process.env.AWS_SECRET_KEY || '';
  if (!accessKeyId || !secretAccessKey) throw new Error('AWS_ACCESS_KEY / AWS_SECRET_KEY missing from .env');
  return { accessKeyId, secretAccessKey };
}

export const defaultRegion = () => process.env.AWS_REGION || 'ap-south-1';

const cache = new Map<string, any>();
function client<T>(kind: string, region: string, make: (cfg: any) => T): T {
  const key = `${kind}:${region}`;
  if (!cache.has(key)) cache.set(key, make({ region, credentials: credentials(), maxAttempts: 4 }));
  return cache.get(key);
}
export const ec2 = (region = defaultRegion()) => client('ec2', region, cfg => new EC2Client(cfg));
export const cloudwatch = (region = defaultRegion()) => client('cw', region, cfg => new CloudWatchClient(cfg));
export const cloudtrail = (region = defaultRegion()) => client('ct', region, cfg => new CloudTrailClient(cfg));
export const costExplorer = () => client('ce', 'us-east-1', cfg => new CostExplorerClient(cfg));
export const sts = () => client('sts', defaultRegion(), cfg => new STSClient(cfg));

export class AwsDenied extends Error {
  constructor(public action: string, message: string) { super(message); }
}

/** Sends a read-only command; refuses anything else; turns access-denied into AwsDenied(action). */
export async function readOnly<T = any>(c: { send: (cmd: any) => Promise<any> }, command: any): Promise<T> {
  const name = command?.constructor?.name || '';
  if (!READ_ONLY.test(name)) throw new Error(`refused: ${name} is not a read-only AWS call`);
  try {
    return await c.send(command);
  } catch (e: any) {
    const code = e?.name || e?.Code || '';
    if (/AccessDenied|UnauthorizedOperation|AccessDeniedException|NotAuthorized/i.test(code)) {
      const service = (c as any)?.config?.serviceId || 'aws';
      throw new AwsDenied(`${String(service).toLowerCase()}:${name.replace(/Command$/, '')}`, e.message);
    }
    throw e;
  }
}

/** Every page of a paginated read-only call (NextToken / nextToken). */
export async function readAll<T = any>(c: any, make: (token?: string) => any, pick: (r: any) => T[]): Promise<T[]> {
  const out: T[] = [];
  let token: string | undefined;
  for (let i = 0; i < 200; i++) {
    const r = await readOnly(c, make(token));
    out.push(...(pick(r) || []));
    token = r.NextToken || r.nextToken;
    if (!token) break;
  }
  return out;
}

/** The identity these keys act as (used to tell the platform's own actions from others in CloudTrail). */
export async function whoAmI(): Promise<{ account: string; arn: string }> {
  const r: any = await readOnly(sts(), new GetCallerIdentityCommand({}));
  return { account: r.Account, arn: r.Arn };
}
