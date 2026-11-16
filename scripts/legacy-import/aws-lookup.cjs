// Read-only AWS lookup for the legacy import: DescribeRegions + DescribeInstances only, nothing else.
// Usage: node scripts/legacy-import/aws-lookup.cjs <out.json> <ids.txt>   (ids.txt: one instance id per line,
//        from: python scripts/legacy-import/convert.py --dump <legacy.sql> --print-instance-ids)
// Uses the AWS SDK installed in test/ (npm ci in test/).
// Credentials come from the repo's .env (AWS_ACCESS_KEY / AWS_SECRET_KEY), never printed.
const fs = require('fs');
const path = require('path');
const repo = path.resolve(__dirname, '..', '..');
const { EC2Client, DescribeRegionsCommand, DescribeInstancesCommand } = require(path.join(repo, 'test/node_modules/@aws-sdk/client-ec2'));
const env = Object.fromEntries(fs.readFileSync(path.join(repo, '.env'), 'utf8').split(/\r?\n/)
  .map(l => l.match(/^\s*([A-Z_]+)\s*=\s*(.*)$/)).filter(Boolean).map(m => [m[1], m[2].trim().replace(/^"|"$/g, '')]));
const credentials = { accessKeyId: env.AWS_ACCESS_KEY, secretAccessKey: env.AWS_SECRET_KEY };
const [out, idsFile] = process.argv.slice(2);
const ids = [...new Set(fs.readFileSync(idsFile, 'utf8').split(/\s+/).filter(x => /^i-[0-9a-f]{8,17}$/.test(x)))].sort();
(async () => {
  const home = new EC2Client({ region: env.AWS_REGION || 'ap-south-1', credentials });
  const regions = (await home.send(new DescribeRegionsCommand({}))).Regions.map(r => r.RegionName);
  const found = {};
  let left = new Set(ids);
  for (const region of regions) {
    if (!left.size) break;
    const c = new EC2Client({ region, credentials });
    try {
     const pending = [...left];
     for (let k = 0; k < pending.length; k += 150) {
      let token;
      do {
      const res = await c.send(new DescribeInstancesCommand({ Filters: [{ Name: 'instance-id', Values: pending.slice(k, k + 150) }], NextToken: token }));
      token = res.NextToken;
      for (const r of res.Reservations || []) for (const i of r.Instances || []) {
        const tag = k => ((i.Tags || []).find(t => t.Key === k) || {}).Value || null;
        found[i.InstanceId] = { region, az: i.Placement && i.Placement.AvailabilityZone, type: i.InstanceType, state: i.State && i.State.Name,
          name: tag('Name'), privateIp: i.PrivateIpAddress || null, vpcId: i.VpcId || null, subnetId: i.SubnetId || null,
          launchTime: i.LaunchTime, tags: Object.fromEntries((i.Tags || []).map(t => [t.Key, t.Value])) };
        left.delete(i.InstanceId);
      }
      } while (token);
     }
    } catch (e) { if (!/AuthFailure|UnauthorizedOperation|OptInRequired/.test(e.name)) console.error(region, e.name); }
  }
  fs.writeFileSync(out, JSON.stringify({ lookedUpAt: new Date().toISOString(), regionsSearched: regions.length, found, notFound: [...left] }, null, 2));
  console.log(`regions searched ${regions.length}, found ${Object.keys(found).length}, not found ${left.size}`);
})().catch(e => { console.error('lookup failed:', e.name, e.message); process.exit(1); });
