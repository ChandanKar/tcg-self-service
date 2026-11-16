#!/usr/bin/env python3
"""Converts a backup of the legacy self-service portal into data files for a new install.

Input : the legacy mysqldump (tables external_user_master, mcube_user_registration,
        vm_registration_master, vm_user_mapping, mcube_user_audit_trail) and an AWS lookup
        of its instance ids (aws-lookup.cjs, read only).
Output: five SQL files that load, in order, into a fresh install (V1 baseline + V2 defaults):
          01-users.sql         user details
          02-environments.sql  environments, one default group each, VMs and their AWS details
          03-audit.sql         start/stop history (links to users and environments are empty)
          04-access.sql        user to environment access (Operator)
          05-audit-links.sql   links the history to users and environments
        plus report.md (counts, rules, every conflict and fallback) and manifest.json (sha256).
Nothing in the schema changes: the files hold rows only. Same input -> byte-identical output,
because every id is a UUIDv5 of a legacy id and nothing depends on the time of the run.

Usage: python convert.py --dump <legacy.sql> --aws <instances.json> --out <dir>
                         [--tz-offset +05:30] [--as-of 2026-11-15]
See docs/runbook-new-install.md for loading the files.
"""
import argparse
import collections
import datetime as dt
import hashlib
import json
import os
import re
import sys
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from legacy_dump import read_dump  # noqa: E402

NAMESPACE = uuid.uuid5(uuid.NAMESPACE_URL, 'https://selfservice.tcgdigital.com/legacy-import')
MARK = 'Imported from the legacy portal'
DEFAULT_ACCOUNT = '00000000-0000-0000-0000-000000000001'
SERVICE_EMAIL = 'legacy-import@selfservice.invalid'
FALLBACK_REGION = 'ap-south-1'
AUDIT_ACTIONS = {  # legacy action -> (action_type, action_status)
    'STARTED': ('VM_START_REQUESTED', 'succeeded'),
    'STOPPED': ('VM_STOP_REQUESTED', 'succeeded'),
    'STARTED_TIMEOUT': ('VM_START_FAILED', 'failed'),
}
SIGN_IN_OUT = {'LOGIN', 'LOGOUT'}  # not imported (owner's decision)
AWS_STATES = {'running': 'RUNNING', 'stopped': 'STOPPED', 'pending': 'STARTING', 'stopping': 'STOPPING',
              'shutting-down': 'STOPPING', 'terminated': 'TERMINATED'}
CHUNK = 500


# ------------------------------------------------------------------------------------------ helpers
def uid(kind, key):
    return str(uuid.uuid5(NAMESPACE, f'{kind}:{key}'))


def sql(v):
    if v is None:
        return 'NULL'
    if isinstance(v, bool):
        return '1' if v else '0'
    if isinstance(v, int):
        return str(v)
    return "'" + str(v).replace('\\', '\\\\').replace("'", "''") + "'"


def jsn(o):
    return json.dumps(o, separators=(',', ':'), ensure_ascii=False)


def clean(s):
    """Non-breaking spaces become spaces; surrounding whitespace goes. None stays None."""
    return None if s is None else s.replace('\xa0', ' ').strip()


def env_slug(name):
    """The old V4 rule: anything but letters, digits and '-' becomes '-', runs collapse, lower case."""
    return re.sub('-+', '-', re.sub('[^a-zA-Z0-9-]', '-', name or '')).lower()


def vm_slug(name):
    return re.sub('-+', '-', re.sub('[^a-z0-9-]', '-', (name or '').lower())).strip('-') or 'vm'


def unnamed(instance_id):
    """The platform's placeholder for a VM without a name (old V90 rule)."""
    return 'unnamed-' + hashlib.sha256(instance_id.encode()).hexdigest()[:6]


def instance_ids(text):
    seen = []
    for i in re.findall(r'i-[0-9a-f]{8,17}', text or ''):
        if i not in seen:
            seen.append(i)
    return seen


def project_code(tags):
    """'ProjectCode', 'Project Code' (also with stray spaces) are one tag; empty values do not count."""
    for k, v in (tags or {}).items():
        if re.sub(r'[\s\xa0_-]+', '', k).lower() == 'projectcode' and (v or '').replace('\xa0', ' ').strip():
            return v.replace('\xa0', ' ').strip()
    return None


def local_to_utc(value, offset_minutes):
    """A legacy DATETIME (server-local time) as UTC 'YYYY-MM-DD HH:MM:SS'; None when missing or invalid."""
    if not value or value.startswith('0000'):
        return None
    try:
        t = dt.datetime.strptime(value, '%Y-%m-%d %H:%M:%S')
    except ValueError:
        return None
    return (t - dt.timedelta(minutes=offset_minutes)).strftime('%Y-%m-%d %H:%M:%S')


def valid_timestamp(value):
    return value if value and not value.startswith('0000') else None


def inserts(table, columns, rows):
    out = []
    for k in range(0, len(rows), CHUNK):
        out.append(f"INSERT INTO {table} ({', '.join(columns)}) VALUES\n"
                   + ',\n'.join('(' + ', '.join(sql(v) for v in r) + ')' for r in rows[k:k + CHUNK]) + ';')
    return out


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()


# ------------------------------------------------------------------------------------------ converter
class Conversion:
    def __init__(self, tables, aws, source, tz_offset_minutes, as_of):
        self.t = tables
        self.aws_found = aws.get('found', {})
        self.aws_at = aws.get('lookedUpAt')
        self.aws_regions = aws.get('regionsSearched')
        self.source = source
        self.tz = tz_offset_minutes
        self.as_of = as_of + ' 00:00:00'
        self.report = collections.OrderedDict()
        self.notes = collections.defaultdict(list)

    # ---- 1. users
    def users(self):
        rows, by_email, by_login = [], {}, {}
        legacy = sorted(self.t['external_user_master'], key=lambda r: int(r['id']))
        last = {}
        for a in self.t['mcube_user_audit_trail']:
            key = (clean(a['USER_ID']) or '').lower()
            d = a['ACTION_DATE']
            if key and d and not d.startswith('0000') and d > last.get(key, ''):
                last[key] = d
        self.service_user = uid('user', 'legacy-import')
        rows.append([self.service_user, SERVICE_EMAIL, 'Legacy import', False, False, False, True,
                     self.as_of, self.as_of, None, None, None, None])
        usernames = set()
        self.user_by_legacy = {}
        for u in legacy:
            email = (clean(u['email_address']) or '').lower()
            if not email:
                self.notes['users without an email (not imported)'].append(f"legacy user #{u['id']}")
                continue
            if email in by_email:
                self.notes['duplicate emails (second not imported)'].append(f"legacy user #{u['id']}")
                continue
            username = clean(u['login_id'])
            if username and username.lower() in usernames:
                self.notes['duplicate login ids (username left empty)'].append(f"legacy user #{u['id']}")
                username = None
            if username:
                usernames.add(username.lower())
            display = clean(u['user_name']) or email.split('@')[0]
            seen = last.get(email) or last.get((clean(u['login_id']) or '').lower())
            user_id = uid('user', u['id'])
            created = valid_timestamp(u['create_date']) or self.as_of
            rows.append([user_id, email, display, False, False, True, False, created, created,
                         local_to_utc(seen, self.tz) if seen else None, username, clean(u['company_name']), int(u['id'])])
            by_email[email] = user_id
            if u['login_id']:
                by_login[(clean(u['login_id']) or '').lower()] = user_id
            self.user_by_legacy[u['id']] = user_id
            if u['is_active'] not in ('Y', 'y'):
                self.notes['legacy users not marked active (imported active, owner decision)'].append(f"legacy user #{u['id']}")
        reg_rows = []
        for r in sorted(self.t.get('mcube_user_registration', []), key=lambda r: int(r['ID'])):
            email = (clean(r['USER_ID']) or '').lower()
            if not email or email in by_email:
                self.notes['portal registrations already imported as users'].append(f"registration #{r['ID']}")
                continue
            display = clean(' '.join(x for x in [clean(r['FIRST_NAME']), clean(r['LAST_NAME'])] if x)) or email.split('@')[0]
            created = local_to_utc(r['CREATION_DATE'], self.tz) or self.as_of
            user_id = uid('registration', r['ID'])
            reg_rows.append([user_id, email, display, False, False, False, False, created, created,
                             None, None, None, None])
            by_email[email] = user_id
            self.notes['portal registrations imported as inactive users'].append(
                f"registration #{r['ID']} (legacy created by: {clean(r['CREATED_BY']) or '-'})")
        self.user_by_email, self.user_by_login = by_email, by_login
        cols = ['user_id', 'email', 'display_name', 'admin', 'env_admin', 'is_active', 'is_service_account',
                'created_at', 'updated_at', 'last_login_at', 'username', 'company_name', 'legacy_user_id']
        self.report['users'] = {'legacy users': len(legacy), 'imported active': len(rows) - 1,
                                'portal registrations (inactive)': len(reg_rows), 'service account (inactive)': 1,
                                'total rows': len(rows) + len(reg_rows),
                                'with a last login from the legacy history': sum(1 for r in rows if r[9]),
                                'passwords carried': 0}
        return ['-- 1. User details: legacy users (active, no password; Microsoft sign-in or an admin sets one),',
                '--    the two old portal registrations (inactive) and the inactive "Legacy import" service account',
                '--    recorded as "granted by" on imported access.'] + inserts('app_user', cols, rows + reg_rows)

    # ---- 2. environments, groups, VMs
    def environments(self):
        legacy = sorted(self.t['vm_registration_master'], key=lambda r: int(r['id']))
        info, names = {}, set()
        for v in legacy:
            ids = instance_ids(v['instance_id'])
            found = [i for i in ids if i in self.aws_found]
            active = v['is_active'] in ('Y', 'y') and bool(found)
            reason = None if active else ('inactive in the legacy portal' if v['is_active'] not in ('Y', 'y')
                                          else 'no instance left in AWS')
            name = env_slug(clean(v['vm_name']))
            base, n = name, 2
            while name in names:
                name = f'{base}-{n}'
                n += 1
            if name != base:
                self.notes['environment names changed to stay unique'].append(f"legacy VM #{v['id']}: {base} -> {name}")
            names.add(name)
            info[v['id']] = dict(row=v, ids=ids, found=found, active=active, reason=reason, name=name)
        # each instance once: the lowest-numbered active environment that lists it, else the lowest inactive one
        owner, listed = {}, collections.defaultdict(list)
        for e in sorted(info.values(), key=lambda e: (not e['active'], int(e['row']['id']))):
            for i in e['ids']:
                owner.setdefault(i, e['row']['id'])
        for e in info.values():
            for i in e['ids']:
                listed[i].append(int(e['row']['id']))
        for i, ls in sorted(listed.items()):
            if len(ls) > 1:
                self.notes['instances listed under several legacy VMs (imported once)'].append(
                    f"{i}: listed in #{', #'.join(map(str, sorted(ls)))}; imported in #{owner[i]}")
        env_rows, group_rows, vm_rows, detail_rows = [], [], [], []
        self.env_by_legacy, self.env_match = {}, {}
        self.active_env_ids = {uid('env', vid) for vid, e in info.items() if e['active']}
        counts = collections.Counter()
        for vid, e in info.items():
            v = e['row']
            env_id, group_id = uid('env', vid), uid('group', vid)
            self.env_by_legacy[vid] = env_id
            self.env_match[(clean(v['vm_name']) or '').lower()] = env_id
            regions = sorted({self.aws_found[i]['region'] for i in e['found']})
            codes = sorted({c for c in (project_code(self.aws_found[i].get('tags')) for i in e['found']) if c})
            meta = collections.OrderedDict()
            if len(regions) == 1:
                meta['region'] = regions[0]
            meta['defaultCloudProvider'] = 'AWS'
            meta['legacyVmId'] = int(vid)
            meta['legacyVmName'] = v['vm_name']
            meta['legacyActive'] = v['is_active']
            meta['legacyInstanceIds'] = e['ids']
            meta['legacyStartApi'] = v['start_vm_api']
            meta['legacyStopApi'] = v['stop_vm_api']
            meta['legacyCreateDate'] = v['create_date']
            meta['legacySource'] = self.source
            if len(codes) == 1:
                meta['projectCode'] = codes[0]
            elif codes:
                meta['projectCodes'] = codes
            if e['reason']:
                meta['importedInactiveReason'] = e['reason']
            created = valid_timestamp(v['create_date']) or self.as_of
            display = clean(v['vm_name']) or e['name']
            env_rows.append([env_id, e['name'], display, f"{MARK} (legacy VM #{vid})", e['active'], created, created,
                             jsn(meta), DEFAULT_ACCOUNT])
            group_rows.append([group_id, env_id, 'default-group', 'Default Group', f'Default group for {display}', 1,
                               created, created, jsn({'isDefault': True, 'legacyVmId': int(vid)})])
            counts['environments active' if e['active'] else f"environments inactive ({e['reason']})"] += 1
            vm_names, seq = set(), 0
            for pos, i in enumerate(e['ids'], 1):
                if owner[i] != vid:
                    continue
                seq += 1
                a = self.aws_found.get(i)
                display_vm = (clean(a.get('name')) if a else None) or unnamed(i)
                vm_name, base, n = vm_slug(display_vm), vm_slug(display_vm), 2
                while vm_name in vm_names:
                    vm_name = f'{base}-{n}'
                    n += 1
                vm_names.add(vm_name)
                region = a['region'] if a else (regions[0] if len(regions) == 1 else FALLBACK_REGION)
                if not a:
                    counts['VMs not found in AWS'] += 1
                status = AWS_STATES.get(a['state'], 'UNKNOWN') if a else 'NOT_FOUND'
                vm_meta = collections.OrderedDict([('legacyVmId', int(vid)), ('legacyVmName', v['vm_name']),
                                                   ('instanceSequence', pos)])
                if len(listed[i]) > 1:
                    vm_meta['alsoListedIn'] = [x for x in sorted(listed[i]) if x != int(vid)]
                vm_meta['awsLookup'] = {'at': self.aws_at, 'found': bool(a), 'state': a['state'] if a else None}
                vm_id = uid('vm', i)
                vm_rows.append([vm_id, group_id, vm_name, display_vm, f"{MARK} (legacy VM #{vid}, instance {pos})",
                                'AWS', region, i, 'DEV', seq, status, created, created, jsn(vm_meta), e['active'],
                                DEFAULT_ACCOUNT])
                detail_rows.append([uid('vmpd', i), vm_id, 'AWS', region, a.get('az') if a else None,
                                    a.get('type') if a else None, a.get('privateIp') if a else None,
                                    a.get('vpcId') if a else None, a.get('subnetId') if a else None,
                                    jsn(a.get('tags')) if a and a.get('tags') else None, created, created])
                counts['VMs in active environments' if e['active'] else 'VMs in inactive environments'] += 1
                if e['active']:
                    counts[f'VMs in active environments, AWS state {a["state"] if a else "not found"}'] += 1
            if not e['found'] and e['ids']:
                counts['environments with no instance left in AWS'] += 1
            counts['environments with one project code' if len(codes) == 1 else
                   ('environments with several project codes' if codes else 'environments without a project code')] += 1
        self.report['environments'] = {'legacy VM rows': len(legacy), **dict(sorted(counts.items())),
                                       'groups': len(group_rows), 'VMs (each instance once)': len(vm_rows),
                                       'AWS lookup': f'{self.aws_at}, {self.aws_regions} regions searched'}
        out = ['-- 2. Environments (one per legacy VM row), one Default Group each, a VM per instance with its AWS details.',
               '--    Active = active in the legacy portal AND at least one instance still in AWS.']
        out += inserts('environment', ['environment_id', 'name', 'display_name', 'description', 'is_active',
                                       'created_at', 'updated_at', 'metadata', 'aws_account_ref'], env_rows)
        out += inserts('vm_group', ['group_id', 'environment_id', 'name', 'display_name', 'description',
                                    'sequence_position', 'created_at', 'updated_at', 'metadata'], group_rows)
        out += inserts('vm', ['vm_id', 'group_id', 'name', 'display_name', 'description', 'provider', 'region',
                              'provider_vm_id', 'vm_type', 'sequence_position', 'status', 'created_at', 'updated_at',
                              'metadata', 'is_active', 'aws_account_ref'], vm_rows)
        out += inserts('vm_provider_details', ['vm_provider_detail_id', 'vm_id', 'provider', 'region_code',
                                               'availability_zone', 'instance_type', 'private_ip', 'vpc_id',
                                               'subnet_id', 'tags', 'created_at', 'updated_at'], detail_rows)
        return out

    # ---- 3. audit
    def audit(self):
        rows, skipped = [], collections.Counter()
        self.audit_user, self.audit_env = collections.defaultdict(list), collections.defaultdict(list)
        unmatched_users, unmatched_envs = collections.Counter(), collections.Counter()
        for a in sorted(self.t['mcube_user_audit_trail'], key=lambda r: int(r['ID'])):
            action = clean(a['ACTION']) or ''
            if action in SIGN_IN_OUT:
                skipped[f'{action} (sign-in/sign-out, owner decision)'] += 1
                continue
            if action not in AUDIT_ACTIONS:
                skipped['project codes and other values in the action column (owner decision)'] += 1
                continue
            when = local_to_utc(a['ACTION_DATE'], self.tz)
            if not when:
                skipped['no valid date'] += 1
                self.notes['audit rows without a valid date (not imported)'].append(f"audit #{a['ID']}")
                continue
            action_type, status = AUDIT_ACTIONS[action]
            audit_id = uid('audit', a['ID'])
            server = clean(a['SERVER_NAME'])
            actor = clean(a['USER_ID'])
            details = f"{MARK} (audit #{a['ID']}, {action} at {a['ACTION_DATE']} India time)"
            if (a['SERVER_NAME'] or '') != (server or ''):
                details += f" [legacy server name: {a['SERVER_NAME']!r}]"
            if a['USER_NAME'] and clean(a['USER_NAME']) != actor:
                details += f" [legacy user name: {clean(a['USER_NAME'])}]"
            rows.append([audit_id, action_type, 'ENVIRONMENT', (server or '')[:255] or None, status, details, when,
                         (actor or '')[:255] or None])
            key = (actor or '').lower()
            user_id = self.user_by_email.get(key) or self.user_by_login.get(key)
            if user_id:
                self.audit_user[user_id].append(audit_id)
            else:
                unmatched_users[actor or '(empty)'] += 1
            env_id = self.env_match.get((server or '').lower())
            if env_id:
                self.audit_env[env_id].append(audit_id)
            else:
                unmatched_envs[server or '(empty)'] += 1
        linked_u = sum(len(v) for v in self.audit_user.values())
        linked_e = sum(len(v) for v in self.audit_env.values())
        self.report['audit'] = {'legacy rows': len(self.t['mcube_user_audit_trail']), 'imported': len(rows),
                                **{f'imported as {AUDIT_ACTIONS[k][0]} ({k})': sum(1 for r in self.t['mcube_user_audit_trail']
                                   if clean(r['ACTION']) == k and local_to_utc(r['ACTION_DATE'], self.tz)) for k in AUDIT_ACTIONS},
                                **{f'not imported: {k}': n for k, n in sorted(skipped.items())},
                                'linked to a user (file 5)': linked_u, 'linked to an environment (file 5)': linked_e,
                                'without a user link': len(rows) - linked_u, 'without an environment link': len(rows) - linked_e,
                                'time conversion': f'legacy DATETIME is local time, UTC = local - {self.tz} minutes'}
        self.notes['history: actors with no matching user (rows)'] = [f'{k}: {n}' for k, n in unmatched_users.most_common()]
        self.notes['history: server names with no matching environment (rows)'] = [f'{k}: {n}' for k, n in unmatched_envs.most_common()]
        return ['-- 3. Start/stop history. user_id and environment_id are filled by 05-audit-links.sql.'] + inserts(
            'audit_log', ['audit_id', 'action_type', 'target_type', 'target_name', 'action_status', 'details',
                          'created_at', 'actor_email'], rows)

    # ---- 4. access
    def access(self):
        rows, skipped, counts = [], collections.Counter(), collections.Counter()
        for m in sorted(self.t['vm_user_mapping'], key=lambda r: int(r['id'])):
            if m['auth'] != '1':
                skipped['auth = 0 ("no access" rows, owner decision)'] += 1
                continue
            user_id, env_id = self.user_by_legacy.get(m['user_id']), self.env_by_legacy.get(m['vm_id'])
            if not user_id or not env_id:
                skipped['grant whose user or environment was not imported'] += 1
                self.notes['grants not imported (missing user or environment)'].append(f"mapping #{m['id']}")
                continue
            status = 'ACTIVE' if m['is_active'] in ('Y', 'y') else 'REVOKED'
            rows.append([uid('access', m['id']), env_id, user_id, 'USER', self.service_user,
                         valid_timestamp(m['create_date']) or self.as_of, status,
                         f"{MARK} (mapping #{m['id']})", 'ENVIRONMENT', env_id, 'DIRECT'])
            counts[status] += 1
        on_active = sum(1 for r in rows if r[1] in self.active_env_ids)
        self.report['access'] = {'legacy mapping rows': len(self.t['vm_user_mapping']), 'imported (Operator)': len(rows),
                                 'on active environments': on_active, 'on inactive environments': len(rows) - on_active,
                                 **{f'status {k}': n for k, n in sorted(counts.items())},
                                 **{f'not imported: {k}': n for k, n in sorted(skipped.items())}}
        return ['-- 4. Access: each legacy grant (auth = 1) becomes Operator (USER) on the whole environment, no expiry.',
                '--    Grants on inactive environments are kept; they apply again if the environment is reactivated.'] + inserts(
            'environment_access', ['access_id', 'environment_id', 'user_id', 'access_level', 'granted_by_user_id',
                                   'granted_at', 'status', 'notes', 'scope_type', 'scope_id', 'initiation'], rows)

    # ---- 5. links
    def links(self):
        out = ['-- 5. Links the imported history to users (and the name they had) and to environments.',
               '--    Matching was done by the converter: legacy user id = email or login id; legacy server name = environment.']
        for user_id in sorted(self.audit_user):
            ids = sorted(self.audit_user[user_id])
            for k in range(0, len(ids), CHUNK):
                out.append(f"UPDATE audit_log SET user_id = {sql(user_id)}, actor_name = "
                           f"(SELECT display_name FROM app_user WHERE user_id = {sql(user_id)}) WHERE audit_id IN ("
                           + ', '.join(sql(i) for i in ids[k:k + CHUNK]) + ');')
        for env_id in sorted(self.audit_env):
            ids = sorted(self.audit_env[env_id])
            for k in range(0, len(ids), CHUNK):
                out.append(f"UPDATE audit_log SET environment_id = {sql(env_id)}, target_id = {sql(env_id)} WHERE audit_id IN ("
                           + ', '.join(sql(i) for i in ids[k:k + CHUNK]) + ');')
        return out

    def run(self):
        files = collections.OrderedDict()
        files['01-users.sql'] = self.users()
        files['02-environments.sql'] = self.environments()
        files['03-audit.sql'] = self.audit()
        files['04-access.sql'] = self.access()
        files['05-audit-links.sql'] = self.links()
        return files


# ------------------------------------------------------------------------------------------ output
def header(name, source, dump_sha, aws_sha, tz):
    return [f'-- {name}: legacy self-service portal -> new install (V1 baseline + V2 defaults)',
            f'-- source: {source} (sha256 {dump_sha})',
            f'-- AWS lookup sha256 {aws_sha}; legacy local time = UTC{tz}',
            '-- Load the five files in order (01..05) into an install that holds only V1+V2, with the app stopped.',
            '-- Rows only: no table, column or index changes. Generated by scripts/legacy-import/convert.py.',
            'SET NAMES utf8mb4;', "SET time_zone = '+00:00';", 'SET FOREIGN_KEY_CHECKS = 1;', 'START TRANSACTION;', '']


def write_report(path, conv, manifest):
    lines = ['# Legacy import report', '',
             f"Source: `{manifest['source']}` (sha256 `{manifest['dumpSha256']}`)  ",
             f"AWS lookup: sha256 `{manifest['awsSha256']}`  ",
             f"Legacy local time: UTC{manifest['tzOffset']}  ", '',
             '## Files', '', '| File | Statements | sha256 |', '|---|---|---|']
    for f in manifest['files']:
        lines.append(f"| {f['name']} | {f['statements']} | `{f['sha256']}` |")
    for section, data in conv.report.items():
        lines += ['', f'## {section.capitalize()}', '', '| | |', '|---|---|']
        lines += [f'| {k} | {v} |' for k, v in data.items()]
    lines += ['', '## Rules applied', '',
              '- Users: all legacy users active, no password (Microsoft sign-in, or an admin sets one); username = login id.',
              '- Environment active = active in the legacy portal and at least one instance still in AWS.',
              '- One "Default Group" per environment; every instance imported once (lowest active legacy VM, else lowest inactive).',
              '- VM name = AWS Name tag, else unnamed-<6 hex>; AWS tags, region, zone, type, IP, VPC, subnet kept.',
              '- Environment metadata keeps the legacy name, instance list, start/stop API URLs, created date and the project code.',
              '- Access: auth = 1 -> Operator (USER) on the whole environment; auth = 0 not imported.',
              '- History: STARTED / STOPPED / STARTED_TIMEOUT imported; sign-in/sign-out and project codes not imported.',
              '- Not carried: legacy passwords (plain text).',
              '', '## Details']
    for title, items in conv.notes.items():
        lines += ['', f'### {title} ({len(items)})', '']
        lines += [f'- {x}' for x in items] if items else ['- none']
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines) + '\n')


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--dump', required=True)
    p.add_argument('--aws', help='output of aws-lookup.cjs')
    p.add_argument('--out')
    p.add_argument('--print-instance-ids', action='store_true',
                   help='print the distinct instance ids of the dump (input for aws-lookup.cjs) and stop')
    p.add_argument('--tz-offset', default='+05:30', help='legacy server local time offset from UTC (default +05:30)')
    p.add_argument('--as-of', default=None, help='date used where the legacy data has none (default: from the file name)')
    args = p.parse_args(argv)
    if args.print_instance_ids:
        ids = sorted({i for v in read_dump(args.dump).get('vm_registration_master', []) for i in instance_ids(v['instance_id'])})
        print('\n'.join(ids))
        return 0
    if not args.aws or not args.out:
        p.error('--aws and --out are required')
    m = re.fullmatch(r'([+-])(\d\d):(\d\d)', args.tz_offset)
    if not m:
        p.error('--tz-offset must look like +05:30')
    tz = (1 if m.group(1) == '+' else -1) * (int(m.group(2)) * 60 + int(m.group(3)))
    source = os.path.basename(args.dump)
    as_of = args.as_of or (re.search(r'(\d{4}-\d\d-\d\d)', source) or [None, '2026-01-01'])[1]
    tables = read_dump(args.dump)
    for t in ['external_user_master', 'vm_registration_master', 'vm_user_mapping', 'mcube_user_audit_trail']:
        if t not in tables:
            p.error(f'the dump has no table {t}')
    tables.setdefault('mcube_user_registration', [])
    with open(args.aws, encoding='utf-8') as f:
        aws = json.load(f)
    conv = Conversion(tables, aws, source, tz, as_of)
    files = conv.run()
    os.makedirs(args.out, exist_ok=True)
    dump_sha, aws_sha = sha256_file(args.dump), sha256_file(args.aws)
    manifest = {'source': source, 'dumpSha256': dump_sha, 'awsSha256': aws_sha, 'tzOffset': args.tz_offset,
                'asOf': as_of, 'files': [], 'counts': conv.report}
    for name, body in files.items():
        path = os.path.join(args.out, name)
        with open(path, 'w', encoding='utf-8', newline='\n') as f:
            f.write('\n'.join(header(name, source, dump_sha, aws_sha, args.tz_offset) + body + ['', 'COMMIT;', '']))
        manifest['files'].append({'name': name, 'statements': len(body), 'sha256': sha256_file(path)})
    with open(os.path.join(args.out, 'manifest.json'), 'w', encoding='utf-8', newline='\n') as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
        f.write('\n')
    write_report(os.path.join(args.out, 'report.md'), conv, manifest)
    for f in manifest['files']:
        print(f"{f['name']}: {f['statements']} statements, sha256 {f['sha256']}")
    return 0


if __name__ == '__main__':
    sys.exit(main())
