#!/usr/bin/env node
// Regenerates the instancePricing block of src/main/resources/pricing/instance-pricing.json from AWS's
// PUBLIC EC2 price list offer files (U26-T03). No AWS credentials and no AWS account are used: it only
// reads https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/current/region_index.json and
// each region's index.csv (200-300 MB each, streamed, nothing kept on disk).
//
// For every instance type and region the file already covers it writes the on-demand hourly rate for
// shared tenancy, capacity status Used, per operating system (the EC2 "operation" code, which is the
// instance's UsageOperation): RunInstances = Linux/UNIX (written without an "operation" field, as
// before), RunInstances:0010 = Red Hat Enterprise Linux, RunInstances:0002 = Windows (license included),
// RunInstances:000g = SUSE Linux. Every other key of the file (specs, maps, RDS) is kept as it is.
// The generation date, price list version and source are recorded in "instancePricingSource".
//
// Usage (from the repository root):
//   node scripts/generate-instance-pricing-public.mjs
//   node scripts/generate-instance-pricing-public.mjs --regions ap-south-1,us-east-1 --operations RunInstances,RunInstances:0010
//   node scripts/generate-instance-pricing-public.mjs --csv-dir <folder with <region>.csv files already downloaded>

import fs from 'node:fs';
import https from 'node:https';
import path from 'node:path';
import readline from 'node:readline';
import { fileURLToPath } from 'node:url';

const HOST = 'https://pricing.us-east-1.amazonaws.com';
const here = path.dirname(fileURLToPath(import.meta.url));
const args = Object.fromEntries(process.argv.slice(2).reduce((pairs, a, i, all) => {
    if (a.startsWith('--')) pairs.push([a.slice(2), all[i + 1]]);
    return pairs;
}, []));
const outFile = args.out ?? path.join(here, '..', 'src', 'main', 'resources', 'pricing', 'instance-pricing.json');
const operations = (args.operations ?? 'RunInstances,RunInstances:0010,RunInstances:0002,RunInstances:000g').split(',');
const LINUX = 'RunInstances';

const raw = fs.readFileSync(outFile, 'utf8');
const eol = raw.includes('\r\n') ? '\r\n' : '\n';
const text = raw.replace(/\r\n/g, '\n'); // a Windows checkout has CRLF; written back the same way
const current = JSON.parse(text);
const typeOrder = [];
for (const e of [...(current.instanceTypes ?? []), ...current.instancePricing]) {
    if (!typeOrder.includes(e.instanceType)) typeOrder.push(e.instanceType);
}
const regionOrder = args.regions ? args.regions.split(',')
    : [...new Set(current.instancePricing.filter(e => e.provider === 'AWS').map(e => e.region))];
const wantedTypes = new Set(typeOrder);
const wantedOps = new Set(operations);

function get(url) {
    return new Promise((resolve, reject) => {
        https.get(url, res => {
            if (res.statusCode !== 200) { res.resume(); reject(new Error(`${url}: HTTP ${res.statusCode}`)); return; }
            resolve(res);
        }).on('error', reject);
    });
}

async function getJson(url) {
    const res = await get(url);
    let body = '';
    res.setEncoding('utf8');
    for await (const chunk of res) body += chunk;
    return JSON.parse(body);
}

/** One CSV line (AWS quotes every non-empty field; no field spans lines). */
function parseCsvLine(line) {
    const out = [];
    let i = 0;
    while (i <= line.length) {
        if (line[i] === '"') {
            let value = '';
            i++;
            while (i < line.length) {
                if (line[i] === '"' && line[i + 1] === '"') { value += '"'; i += 2; }
                else if (line[i] === '"') { i++; break; }
                else value += line[i++];
            }
            out.push(value);
            i++; // the comma
        } else {
            const next = line.indexOf(',', i);
            const end = next < 0 ? line.length : next;
            out.push(line.slice(i, end));
            i = end + 1;
        }
    }
    return out;
}

/** rates: Map "type|operation" -> USD per hour, for one region's offer file. */
async function readRegion(region, versionUrl) {
    const input = args['csv-dir'] ? fs.createReadStream(path.join(args['csv-dir'], `${region}.csv`))
        : await get(HOST + versionUrl.replace(/index\.json$/, 'index.csv'));
    const lines = readline.createInterface({ input, crlfDelay: Infinity });
    const rates = new Map();
    let header = null;
    let meta = {};
    let col;
    for await (const line of lines) {
        if (!header) {
            const cells = parseCsvLine(line);
            if (cells[0] === 'SKU') {
                header = cells;
                col = Object.fromEntries(header.map((h, i) => [h, i]));
            } else {
                meta[cells[0]] = cells[1];
            }
            continue;
        }
        // Cheap pre-filter before parsing the whole line.
        if (!line.includes('"OnDemand"') || !line.includes('"Shared"')) continue;
        const row = parseCsvLine(line);
        if (row[col.TermType] !== 'OnDemand' || row[col.Tenancy] !== 'Shared' || row[col.CapacityStatus] !== 'Used'
            || row[col['Product Family']] !== 'Compute Instance' || row[col.Unit] !== 'Hrs' || row[col.Currency] !== 'USD'
            || row[col['Region Code']] !== region) continue;
        const type = row[col['Instance Type']];
        const op = row[col.operation];
        if (!wantedTypes.has(type) || !wantedOps.has(op)) continue;
        const usd = Number(row[col.PricePerUnit]);
        if (!(usd > 0)) continue;
        const key = `${type}|${op}`;
        if (rates.has(key) && rates.get(key) !== usd) {
            console.warn(`${region} ${key}: two rates (${rates.get(key)}, ${usd}); keeping the first`);
            continue;
        }
        rates.set(key, usd);
    }
    return { rates, meta };
}

const index = await getJson(`${HOST}/offers/v1.0/aws/AmazonEC2/current/region_index.json`);
const byRegion = {};
let version = null;
let published = null;
for (const region of regionOrder) {
    const entry = index.regions[region];
    if (!entry) throw new Error(`No offer file for ${region}`);
    const { rates, meta } = await readRegion(region, entry.currentVersionUrl);
    byRegion[region] = rates;
    version = meta.Version ?? version;
    published = meta['Publication Date'] ?? published;
    console.log(`${region}: ${rates.size} rates (price list ${meta.Version})`);
}

const lines = [];
for (const op of operations) {
    for (const type of typeOrder) {
        for (const region of regionOrder) {
            const usd = byRegion[region].get(`${type}|${op}`);
            if (usd === undefined) {
                if (op === LINUX) console.warn(`No on-demand Linux rate for ${type} in ${region}`);
                continue;
            }
            // Price list rates carry 10 decimals; 6 keep every published digit (price_cache keeps 6 too).
            const rate = Number(usd.toFixed(6));
            const opField = op === LINUX ? '' : ` "operation": "${op}",`;
            lines.push(`    { "provider": "AWS", "instanceType": "${type}", "region": "${region}",${opField} "hourlyRate": ${rate} }`);
        }
    }
}

const generated = new Date().toISOString().slice(0, 10);
const source = {
    generatedAt: generated,
    priceListVersion: version,
    priceListPublicationDate: published,
    source: `${HOST}/offers/v1.0/aws/AmazonEC2/current/region_index.json (public offer files, on-demand, shared tenancy, capacity status Used)`,
    script: 'scripts/generate-instance-pricing-public.mjs',
    operations: 'no operation = RunInstances (Linux/UNIX); RunInstances:0010 = Red Hat Enterprise Linux; RunInstances:0002 = Windows; RunInstances:000g = SUSE Linux',
};
const sourceLine = `  "instancePricingSource": ${JSON.stringify(source)},\n`;

let out = text.replace(/  "instancePricing": \[\n[\s\S]*?\n  \],\n/, `  "instancePricing": [\n${lines.join(',\n')}\n  ],\n`);
if (/^  "instancePricingSource": .*\n/m.test(out)) {
    out = out.replace(/^  "instancePricingSource": .*\n/m, sourceLine);
} else {
    out = out.replace(/(  "instancePricing": \[)/, `${sourceLine}$1`);
}
JSON.parse(out); // still valid JSON
if (!out.includes(lines[0])) throw new Error('The instancePricing block was not found');
fs.writeFileSync(outFile, out.replace(/\n/g, eol));
console.log(`${outFile}: ${lines.length} instance rates, price list ${version} (${published}), generated ${generated}`);
