"""Tests for convert.py on a small made-up legacy dump (no real data). Run: python -m unittest -v test_convert"""
import hashlib
import json
import os
import re
import tempfile
import unittest

import convert

DUMP = r"""-- MySQL dump 10.13  Distrib 5.7.33, for Linux (x86_64)
/*!40101 SET NAMES utf8 */;
CREATE TABLE `external_user_master` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `user_name` varchar(150) DEFAULT NULL,
  `login_id` varchar(75) DEFAULT NULL,
  `password` varchar(300) DEFAULT NULL,
  `user_dn` varchar(75) DEFAULT NULL,
  `email_address` varchar(300) DEFAULT NULL,
  `company_name` varchar(300) DEFAULT NULL,
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `is_active` char(3) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `email_address` (`email_address`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
INSERT INTO `external_user_master` VALUES (1,'Ann O\'Neil','ann@x.com','secret-ann',NULL,'Ann@X.com','Acme','2023-01-01 10:00:00','Y'),(2,'Bob','bob@x.com','secret-bob',NULL,'bob@x.com','Acme','2023-02-01 10:00:00','Y'),(3,'Cy Lee ','cy@ext.com','secret-cy','','cy@ext.com',NULL,'2024-01-01 00:00:00','Y');
CREATE TABLE `mcube_user_audit_trail` (
  `ID` int(11) NOT NULL AUTO_INCREMENT,
  `USER_ID` varchar(300) DEFAULT NULL,
  `USER_NAME` varchar(300) DEFAULT NULL,
  `SERVER_NAME` varchar(300) DEFAULT NULL,
  `ACTION` varchar(100) DEFAULT NULL,
  `ACTION_DATE` datetime DEFAULT NULL,
  PRIMARY KEY (`ID`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
INSERT INTO `mcube_user_audit_trail` VALUES (1000,'ann@x.com','Ann O\'Neil','Env One','STARTED','2023-05-01 10:00:00'),(1001,'bob@x.com','Bob','Env One','LOGIN','2023-06-01 09:00:00'),(1002,'ghost@x.com','Ghost','Nowhere','STOPPED','2023-05-02 10:00:00'),(1003,'ann@x.com','Ann O\'Neil','Env One','LVAI','2023-05-02 11:00:00');
INSERT INTO `mcube_user_audit_trail` VALUES (1004,'Ann@X.com','Ann O\'Neil','Env-One','STARTED_TIMEOUT','2023-05-03 00:00:00'),(1005,'bob@x.com','Bob','Env One','LOGOUT','2023-06-01 10:00:00'),(1006,'bob@x.com','Bob','Env One','STOPPED',NULL);
CREATE TABLE `mcube_user_registration` (
  `ID` int(11) NOT NULL AUTO_INCREMENT,
  `USER_ID` varchar(300) DEFAULT NULL,
  `PASSWORD` varchar(300) DEFAULT NULL,
  `FIRST_NAME` varchar(100) DEFAULT NULL,
  `LAST_NAME` varchar(100) DEFAULT NULL,
  `CREATION_DATE` datetime DEFAULT NULL,
  `CREATED_BY` varchar(300) DEFAULT NULL,
  PRIMARY KEY (`ID`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
INSERT INTO `mcube_user_registration` VALUES (1,'reg@x.com','secret-reg','Reg','User','2020-05-28 12:02:41','admin'),(2,'BOB@x.com','secret-bob2','Bob','Again','2020-05-28 14:01:49','admin');
CREATE TABLE `vm_registration_master` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `vm_name` varchar(150) NOT NULL,
  `start_vm_api` varchar(300) DEFAULT NULL,
  `stop_vm_api` varchar(300) DEFAULT NULL,
  `instance_id` varchar(1000) DEFAULT NULL,
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `is_active` char(3) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
INSERT INTO `vm_registration_master` VALUES (10,'Env One','https://api/start10','https://api/stop10','[\'i-0000000000000000a\',\'i-0000000000000000b\']','2023-01-01 00:00:00','Y'),(11,'Env-One','https://api/start11','https://api/stop11','[\'i-0000000000000000c\']','2023-01-02 00:00:00','Y'),(12,'Gone','','','[\'i-0000000000000000d\']','2023-01-03 00:00:00','Y'),(13,'Old','','','["i-0000000000000000b","i-0000000000000000e"]','2023-01-04 00:00:00','N');
CREATE TABLE `vm_user_mapping` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `user_id` int(11) DEFAULT NULL,
  `vm_id` int(11) DEFAULT NULL,
  `auth` tinyint(1) DEFAULT NULL,
  `create_date` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `is_active` char(3) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
INSERT INTO `vm_user_mapping` VALUES (100,1,10,1,'2023-03-01 00:00:00','Y'),(101,1,11,0,'2023-03-01 00:00:00','Y'),(102,2,13,1,'2023-03-02 00:00:00','Y'),(103,3,10,1,'2023-03-03 00:00:00','N');
"""

AWS = {
    'lookedUpAt': '2026-11-16T00:00:00.000Z', 'regionsSearched': 2, 'notFound': ['i-0000000000000000d'],
    'found': {
        'i-0000000000000000a': {'region': 'ap-south-1', 'az': 'ap-south-1a', 'type': 't3.large', 'state': 'running',
                                'name': 'web', 'privateIp': '10.0.0.1', 'vpcId': 'vpc-1', 'subnetId': 'subnet-1',
                                'tags': {'Name': 'web', 'ProjectCode': 'CDMC'}},
        'i-0000000000000000b': {'region': 'ap-south-1', 'az': 'ap-south-1b', 'type': 't3.small', 'state': 'stopped',
                                'name': None, 'privateIp': None, 'vpcId': None, 'subnetId': None,
                                'tags': {'Project Code ': 'CDMC'}},
        'i-0000000000000000c': {'region': 'us-east-1', 'az': 'us-east-1a', 'type': 'm5.large', 'state': 'running',
                                'name': 'web', 'privateIp': None, 'vpcId': None, 'subnetId': None, 'tags': {'Name': 'web'}},
        'i-0000000000000000e': {'region': 'ap-south-1', 'az': 'ap-south-1a', 'type': 't3.micro', 'state': 'stopped',
                                'name': 'old-db', 'privateIp': None, 'vpcId': None, 'subnetId': None,
                                'tags': {'Name': 'old-db', 'Project Code': 'LVSA'}},
    },
}

U = lambda key: convert.uid('user', key)
E = lambda key: convert.uid('env', key)


class ConvertTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        d = cls.tmp.name
        cls.dump = os.path.join(d, 'legacy-2026-11-15.sql')
        cls.aws = os.path.join(d, 'aws.json')
        with open(cls.dump, 'w', encoding='utf-8', newline='\n') as f:
            f.write(DUMP.replace('Cy Lee ', 'Cy Lee '))
        with open(cls.aws, 'w', encoding='utf-8') as f:
            json.dump(AWS, f)
        cls.out = os.path.join(d, 'out')
        convert.main(['--dump', cls.dump, '--aws', cls.aws, '--out', cls.out])
        cls.f = {n: open(os.path.join(cls.out, n), encoding='utf-8').read() for n in os.listdir(cls.out)}
        cls.manifest = json.loads(cls.f['manifest.json'])

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def rows(self, file, table):
        """Value tuples of the INSERT statements for a table, as raw SQL text per row."""
        body = '\n'.join(s for s in re.findall(rf'INSERT INTO {table} \(.*?\) VALUES\n(.*?);\n', self.f[file] + '\n', re.S))
        return [r for r in re.split(r'\),\n\(', body.strip()[1:-1])] if body else []

    def test_five_sql_files_report_and_manifest(self):
        self.assertEqual(['01-users.sql', '02-environments.sql', '03-audit.sql', '04-access.sql', '05-audit-links.sql'],
                         [x['name'] for x in self.manifest['files']])
        for name in ['01-users.sql', '02-environments.sql', '03-audit.sql', '04-access.sql', '05-audit-links.sql']:
            self.assertIn('START TRANSACTION;', self.f[name])
            self.assertTrue(self.f[name].rstrip().endswith('COMMIT;'))
            self.assertEqual(hashlib.sha256(self.f[name].encode()).hexdigest(),
                             next(x['sha256'] for x in self.manifest['files'] if x['name'] == name))
        self.assertIn('report.md', self.f)

    def test_no_schema_statements_and_no_password(self):
        everything = ''.join(v for k, v in self.f.items() if k.endswith('.sql'))
        self.assertNotRegex(everything, r'(?i)\b(CREATE|ALTER|DROP|TRUNCATE|RENAME)\s+(TABLE|INDEX|DATABASE|VIEW)')
        for secret in ['secret-ann', 'secret-bob', 'secret-cy', 'secret-reg', 'secret-bob2']:
            self.assertNotIn(secret, ''.join(self.f.values()))

    def test_users(self):
        users = self.rows('01-users.sql', 'app_user')
        self.assertEqual(5, len(users))  # service account + 3 legacy + 1 registration (the other is a duplicate email)
        ann = next(r for r in users if U('1') in r)
        self.assertIn("'ann@x.com', 'Ann O''Neil', 0, 0, 1, 0", ann)          # lower-case email, quote escaped, active
        self.assertIn("'ann@x.com', 'Acme', 1", ann)                           # username = login id, company, legacy id
        bob = next(r for r in users if U('2') in r)
        self.assertIn("'2023-06-01 04:30:00'", bob)                            # last login = LOGOUT 10:00 India time -> UTC
        cy = next(r for r in users if U('3') in r)
        self.assertIn("'Cy Lee'", cy)                                          # non-breaking space cleaned, trimmed
        svc = next(r for r in users if convert.SERVICE_EMAIL in r)
        self.assertIn("'Legacy import', 0, 0, 0, 1", svc)                     # inactive service account
        reg = next(r for r in users if 'reg@x.com' in r)
        self.assertIn("'Reg User', 0, 0, 0, 0, '2020-05-28 06:32:41'", reg)    # inactive, India time -> UTC
        self.assertNotIn("'Bob Again'", self.f['01-users.sql'])                # registration with an existing email skipped

    def test_environments_active_rule_names_and_metadata(self):
        envs = self.rows('02-environments.sql', 'environment')
        self.assertEqual(4, len(envs))
        by = {k: next(r for r in envs if E(k) in r) for k in ['10', '11', '12', '13']}
        self.assertIn("'env-one', 'Env One'", by['10'])
        self.assertIn("'env-one-2', 'Env-One'", by['11'])                     # slug collision gets -2
        self.assertRegex(by['10'], r"\(legacy VM #10\)', 1,")                 # active: legacy Y and an instance in AWS
        self.assertRegex(by['12'], r"\(legacy VM #12\)', 0,")                 # legacy Y but no instance left -> inactive
        self.assertIn('"importedInactiveReason":"no instance left in AWS"', by['12'])
        self.assertRegex(by['13'], r"\(legacy VM #13\)', 0,")
        self.assertIn('"importedInactiveReason":"inactive in the legacy portal"', by['13'])
        self.assertIn('"projectCode":"CDMC"', by['10'])                        # 'ProjectCode' and 'Project Code\xa0' agree
        self.assertIn('"projectCodes":["CDMC","LVSA"]', by['13'])                # its instances carry different codes
        self.assertNotIn('projectCode', by['11'])
        self.assertIn('"legacyStartApi":"https://api/start10"', by['10'])
        self.assertIn('"legacyInstanceIds":["i-0000000000000000a","i-0000000000000000b"]', by['10'])
        self.assertIn('"region":"ap-south-1"', by['10'])
        self.assertEqual(4, len(self.rows('02-environments.sql', 'vm_group')))

    def test_vms_each_instance_once(self):
        vms = self.rows('02-environments.sql', 'vm')
        self.assertEqual(5, len(vms))                                          # a, b, c, d, e
        vm = lambda i: next(r for r in vms if f"'{i}'" in r)
        self.assertIn(convert.uid('group', '10'), vm('i-0000000000000000b'))   # shared b goes to the active env 10
        self.assertIn('"alsoListedIn":[13]', vm('i-0000000000000000b'))
        self.assertIn(convert.unnamed('i-0000000000000000b'), vm('i-0000000000000000b'))  # no Name tag
        self.assertIn("'web', 'web'", vm('i-0000000000000000a'))
        self.assertIn("'RUNNING'", vm('i-0000000000000000a'))
        self.assertIn("'us-east-1'", vm('i-0000000000000000c'))
        self.assertIn("'NOT_FOUND'", vm('i-0000000000000000d'))
        self.assertRegex(vm('i-0000000000000000d'), r"'NOT_FOUND', .*', 0, '")  # inactive
        self.assertIn(convert.uid('group', '13'), vm('i-0000000000000000e'))
        self.assertEqual(5, len(self.rows('02-environments.sql', 'vm_provider_details')))
        self.assertIn('"ProjectCode":"CDMC"', self.f['02-environments.sql'])  # AWS tags kept as they are

    def test_audit_mapping_skips_and_time(self):
        audit = self.rows('03-audit.sql', 'audit_log')
        self.assertEqual(3, len(audit))                                        # 1000, 1002, 1004
        row = lambda n: next(r for r in audit if convert.uid('audit', n) in r)
        self.assertIn("'VM_START_REQUESTED', 'ENVIRONMENT', 'Env One', 'succeeded'", row('1000'))
        self.assertIn("'2023-05-01 04:30:00'", row('1000'))                   # 10:00 India time -> 04:30 UTC
        self.assertIn("'VM_STOP_REQUESTED'", row('1002'))
        self.assertIn("'VM_START_FAILED', 'ENVIRONMENT', 'Env-One', 'failed'", row('1004'))
        self.assertIn("'2023-05-02 18:30:00'", row('1004'))                   # crosses midnight
        counts = self.manifest['counts']['audit']
        self.assertEqual(1, counts['not imported: LOGIN (sign-in/sign-out, owner decision)'])
        self.assertEqual(1, counts['not imported: LOGOUT (sign-in/sign-out, owner decision)'])
        self.assertEqual(1, counts['not imported: project codes and other values in the action column (owner decision)'])
        self.assertEqual(1, counts['not imported: no valid date'])

    def test_access(self):
        access = self.rows('04-access.sql', 'environment_access')
        self.assertEqual(3, len(access))                                       # 100, 102, 103 (101 is auth = 0)
        a = lambda n: next(r for r in access if convert.uid('access', n) in r)
        self.assertIn(f"'{E('10')}', '{U('1')}', 'USER', '{convert.uid('user', 'legacy-import')}'", a('100'))
        self.assertIn("'ACTIVE'", a('100'))
        self.assertIn(f"'{E('13')}', '{U('2')}', 'USER'", a('102'))            # grant on an inactive environment kept
        self.assertIn("'REVOKED'", a('103'))                                   # legacy mapping not active
        self.assertEqual(1, self.manifest['counts']['access']['on active environments'] - 1)

    def test_links(self):
        links = self.f['05-audit-links.sql']
        ann_rows = re.search(rf"SET user_id = '{U('1')}'.*?IN \((.*?)\);", links).group(1)
        self.assertIn(convert.uid('audit', '1000'), ann_rows)
        self.assertIn(convert.uid('audit', '1004'), ann_rows)                  # 'Ann@X.com' matches case-insensitively
        self.assertIn(f"SET environment_id = '{E('10')}', target_id = '{E('10')}' WHERE audit_id IN ('{convert.uid('audit', '1000')}')", links)
        self.assertIn(f"SET environment_id = '{E('11')}'", links)
        self.assertNotIn(convert.uid('audit', '1002'), links)                  # ghost user, unknown server: stays unlinked
        self.assertIn('ghost@x.com: 1', self.f['report.md'])

    def test_same_input_same_bytes(self):
        out2 = os.path.join(self.tmp.name, 'out2')
        convert.main(['--dump', self.dump, '--aws', self.aws, '--out', out2])
        for name in sorted(os.listdir(self.out)):
            with open(os.path.join(self.out, name), 'rb') as a, open(os.path.join(out2, name), 'rb') as b:
                self.assertEqual(a.read(), b.read(), name)


if __name__ == '__main__':
    unittest.main()
