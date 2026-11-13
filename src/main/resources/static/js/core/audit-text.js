/**
 * VM Self-Service Platform - Audit rows in words (U12-T08; first written for My activity, U09-T04)
 * AuditText.what(log) -> {text, note, result, tone}: one audit row as a sentence ("Started 5 VMs",
 * "Granted Neha Gupta operator access, 7 days", "Run skipped: environment was locked by Asha Rao"),
 * a note under it (a failure's reason), and Done / Failed / Sent. AuditText.who(log): the person,
 * 'Schedule "Nightly batch"' for a rule's run, or "The platform". Text comes from the row's action,
 * target name and details; never from targetId, so an instance ID the server masked stays masked.
 */
const AuditText = (function() {
    'use strict';

    const VERB = { START: ['start', 'Started'], STOP: ['stop', 'Stopped'], RESTART: ['restart', 'Restarted'] };
    const LEVEL = { USER: 'operator', VIEWER: 'view-only', ENV_ADMIN: 'admin', ADMIN: 'admin' };
    const RUN_ACTIONS = ['AUTOMATION_RULE_TRIGGERED', 'AUTOMATION_RULE_SKIPPED', 'AUTOMATION_RULE_FAILED'];
    // Words kept in capitals when an action name becomes a noun ("VM group", "AWS account").
    const ACRONYMS = { VM: 'VM', VMS: 'VMs', AWS: 'AWS', API: 'API', EKS: 'EKS', RDS: 'RDS', CSV: 'CSV' };
    const CHANGE_VERBS = { CREATED: 'Added', ADDED: 'Added', REGISTERED: 'Added', UPDATED: 'Changed', CHANGED: 'Changed',
        DELETED: 'Deleted', REMOVED: 'Removed', DEACTIVATED: 'Deactivated', ACTIVATED: 'Activated', ENABLED: 'Turned on',
        DISABLED: 'Turned off' };

    function after(text, marker) {
        const s = String(text || '');
        const i = s.indexOf(marker);
        return i < 0 ? '' : s.slice(i + marker.length).trim();
    }

    function trimDot(s) {
        return String(s || '').trim().replace(/\.$/, '');
    }

    function plural(n, one, many) {
        return `${n} ${n === 1 ? one : (many || one + 's')}`;
    }

    function hoursText(minutes) {
        const m = Number(minutes);
        if (!m) return '';
        if (m % 60 === 0) return plural(m / 60, 'hour');
        return plural(m, 'minute');
    }

    const RAW_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

    /** The person a user row is about: their name, or "someone" where older rows kept only an id (U23-T13). */
    function personRef(log) {
        const name = String(log.targetName || '').trim();
        return name && !RAW_ID.test(name) ? name : 'someone';
    }

    /** The row with a raw id as target name left out, so it reads 'Deactivated the user'. */
    function withPerson(log) {
        return RAW_ID.test(String(log.targetName || '').trim()) ? Object.assign({}, log, { targetName: '' }) : log;
    }

    /** "Name (email)" from describeUser, shortened to the name. */
    function personName(s) {
        return String(s || '').replace(/\s*\([^)]*@[^)]*\)\s*$/, '').trim();
    }

    function levelText(s) {
        const key = String(s || '').trim().toUpperCase();
        return LEVEL[key] || String(s || '').toLowerCase();
    }

    function quoted(s) {
        const t = trimDot(s);
        return t ? ` · "${t}"` : '';
    }

    /** "VM_GROUP" -> "VM group" */
    function noun(words) {
        return words.map((w, i) => ACRONYMS[w] || (i === 0 ? w.charAt(0) + w.slice(1).toLowerCase() : w.toLowerCase()))
            .join(' ').replace(/^[A-Z][a-z]/, c => c.toLowerCase());
    }

    /** The action's own label, or "Environment updated", with its target. */
    function fallback(log) {
        const parts = String(log.action || '').split('_');
        const verb = CHANGE_VERBS[parts[parts.length - 1]];
        const target = log.targetName && log.targetName !== log.environmentName ? log.targetName : '';
        if (verb && parts.length > 1) {
            const what = noun(parts.slice(0, -1));
            return target ? `${verb} ${what} "${target}"` : `${verb} the ${what}`;
        }
        const label = log.actionDisplay || String(log.action || '').replace(/_/g, ' ').toLowerCase()
            .replace(/^./, c => c.toUpperCase());
        return target ? `${label} · ${target}` : label;
    }

    /** What a rule's skipped run says after "Skipped: ". */
    function skipReason(details) {
        const why = String(details || '').replace(/^Skipped:\s*/, '');
        const locked = why.match(/^Environment locked by (.+)$/);
        if (locked) return `environment was locked by ${locked[1]}`;
        if (/^Nothing to do\b/.test(why)) return 'nothing to do';
        if (/^by override: /.test(why)) return `skipped by override: ${why.slice('by override: '.length)}`;
        return why ? why.charAt(0).toLowerCase() + why.slice(1) : 'no reason given';
    }

    /**
     * One audit row as a sentence: {text, note, result, tone}. result is Done, Failed or Sent (a
     * request someone else decides; the row itself cannot say whether it is still waiting).
     */
    function what(log) {
        const a = log.action || '';
        const d = log.details || '';
        const failed = log.success === false;
        const verb = VERB[String(log.targetName || '').toUpperCase()] || null;
        let text = null;
        const error = log.errorMessage || d;
        let note = failed ? (after(error, 'Error:') || error || '') : '';
        let sent = false;

        switch (a) {
            case 'OPERATION_STARTED': {
                const trigger = after(d, 'started by the platform:').replace(/\s*\(on behalf of.*$/, '');
                text = verb ? `Asked to ${verb[0]} VMs` : 'Asked to run an operation';
                if (/^lease expired/i.test(trigger)) text = `The platform ${verb ? verb[1].toLowerCase() : 'ran'} VMs when the running time ended`;
                else if (trigger) text = `The platform ${verb ? verb[1].toLowerCase() : 'ran'} VMs (${trimDot(trigger)})`;
                break;
            }
            case 'OPERATION_COMPLETED': {
                const m = /Total VMs:\s*(\d+),\s*Failed:\s*(\d+)/.exec(d);
                const total = m ? Number(m[1]) : null;
                const bad = m ? Number(m[2]) : 0;
                const past = verb ? verb[1] : 'Finished';
                if (total == null) text = `${past} VMs`;
                else if (bad > 0) {
                    text = `${past} ${total - bad} of ${plural(total, 'VM')}`;
                    note = `${plural(bad, 'VM')} did not ${verb ? verb[0] : 'finish'} or ${bad === 1 ? 'was' : 'were'} skipped.`;
                } else text = `${past} ${plural(total, 'VM')}`;
                break;
            }
            case 'OPERATION_FAILED':
                text = verb ? `${verb[1]} VMs` : 'Ran an operation';
                break;
            case 'OPERATION_CANCELLED':
                text = verb ? `Cancelled the ${verb[0]}` : 'Cancelled an operation';
                break;
            case 'VM_START_COMPLETED': case 'VM_START_FAILED': text = `Started ${log.targetName || 'a VM'}`; break;
            case 'VM_STOP_COMPLETED': case 'VM_STOP_FAILED': text = `Stopped ${log.targetName || 'a VM'}`; break;
            case 'VM_START_REQUESTED': text = `Asked to start ${log.targetName || 'a VM'}`; break;
            case 'VM_STOP_REQUESTED': text = `Asked to stop ${log.targetName || 'a VM'}`; break;
            case 'VM_RESTART_REQUESTED': text = `Asked to restart ${log.targetName || 'a VM'}`; break;
            case 'LOCK_ACQUIRED': text = 'Locked' + quoted(after(d, 'Reason:')); break;
            case 'LOCK_RELEASED': text = 'Released lock'; break;
            case 'LOCK_EXTENDED': {
                const m = /Extended by (\d+) minutes/.exec(d);
                text = m ? `Kept the lock ${hoursText(m[1])} longer` : 'Kept the lock longer';
                break;
            }
            case 'LOCK_EXPIRED': text = 'The lock ended'; break;
            case 'LOCK_BROKEN': {
                const holder = trimDot(after(d, 'Original holder:').split('. Reason:')[0]);
                text = `Broke ${holder ? 'the lock held by ' + personName(holder) : 'a lock'}` + quoted(after(d, 'Reason:'));
                break;
            }
            case 'LEASE_STARTED': {
                const m = /Runs for (\d+) minutes/.exec(d);
                text = m ? `Started for ${hoursText(m[1])}` : 'Started, following the lock';
                break;
            }
            case 'LEASE_EXTENDED': {
                const m = /Extended by (\d+) minutes/.exec(d);
                text = m ? `Kept it running ${hoursText(m[1])} longer` : 'Kept it running longer';
                break;
            }
            case 'LEASE_ENDED': text = 'Ended the running time'; break;
            case 'LEASE_EXTENSION_REQUESTED': {
                const m = /extend by (\d+) minutes/.exec(d);
                text = m ? `Asked to keep it running ${hoursText(m[1])} longer` : 'Asked to keep it running longer';
                sent = true;
                break;
            }
            case 'LEASE_EXTENSION_DECIDED': {
                const m = /^(Approved|Denied) a (\d+)-minute extension/.exec(d);
                text = m ? `${m[1]} keeping it running ${hoursText(m[2])} longer` : 'Decided an extension';
                break;
            }
            case 'APPROVAL_REQUESTED': {
                const type = /^(START|EXTEND)/.exec(d);
                text = type && type[1] === 'EXTEND' ? 'Asked for approval to keep it running longer' : 'Asked for approval to start';
                sent = true;
                break;
            }
            case 'APPROVAL_APPROVED':
                text = /waiting for the other approvers/.test(d) ? 'Approved a request (others still to decide)' : 'Approved a request';
                break;
            case 'APPROVAL_DENIED': text = 'Denied a request' + quoted(after(d, ':')); break;
            case 'APPROVAL_CANCELLED': text = 'Cancelled a request'; break;
            case 'APPROVAL_EXPIRED': text = 'A request expired'; break;
            case 'ACCESS_REQUESTED': {
                const level = levelText(after(d, 'Level:'));
                text = level ? `Asked for ${level} access` : 'Asked for access';
                sent = true;
                break;
            }
            case 'ACCESS_GRANTED': {
                const who = personName(after(d, 'Access granted to').split('. Level:')[0]);
                const level = levelText(trimDot(after(d, 'Level:').split('. Approved by')[0]));
                const days = /\. For (\d+ days?)$/.exec(d);
                text = (who ? `Granted ${who} ${level ? level + ' ' : ''}access` : 'Granted access') + (days ? `, ${days[1]}` : '');
                break;
            }
            case 'ACCESS_DENIED': {
                const who = personName(after(d, 'Access denied for').split('. Denied by')[0]);
                text = (who ? `Turned down ${who}'s request` : 'Turned down a request') + quoted(after(d, 'Reason:'));
                break;
            }
            case 'ACCESS_REVOKED': {
                const who = personName(after(d, 'Access revoked for').split('. Revoked by')[0]);
                text = who ? `Removed ${who}'s access` : 'Removed access';
                break;
            }
            case 'ACCESS_LEVEL_CHANGED': {
                const m = /Access level for (.*) changed (\S+) -> (\S+)/.exec(d);
                text = m ? `Changed ${personName(m[1])}'s access to ${levelText(m[3])}` : 'Changed access';
                break;
            }
            case 'IDLE_STOP_SNOOZED': {
                const m = /snoozed for (\d+) h/.exec(d);
                text = m ? `Paused idle auto-stop for ${plural(Number(m[1]), 'hour')}` : 'Resumed idle auto-stop';
                break;
            }
            case 'AUTOMATION_RULE_TRIGGERED':
                text = /\bSTOP\b/.test(d) ? 'Stopped VMs' : 'Started VMs';
                break;
            case 'AUTOMATION_RULE_SKIPPED':
                text = `Run skipped: ${skipReason(d)}`;
                break;
            case 'AUTOMATION_RULE_FAILED':
                text = 'Run failed';
                note = log.errorMessage || d || '';
                break;
            case 'AUTOMATION_RULE_CREATED': text = `Added rule "${log.targetName || ''}"`; break;
            case 'AUTOMATION_RULE_UPDATED': text = `Changed rule "${log.targetName || ''}"`; break;
            case 'AUTOMATION_RULE_DELETED': text = `Deleted rule "${log.targetName || ''}"`; break;
            case 'HOLIDAY_CALENDAR_CHANGED': text = `Changed holiday calendar "${log.targetName || ''}"`; break;
            case 'USER_UPDATED':
                // U23-T13: choosing one's own password read 'Changed user "uat.viewer@example.com"'.
                if (/^Password set/.test(d)) {
                    text = log.userId && log.userId === log.targetId ? 'Chose a new password' : `Set a password for ${personRef(log)}`;
                } else text = fallback(withPerson(log));
                break;
            case 'USER_DEACTIVATED': text = fallback(withPerson(log)); break;
            default:
                text = fallback(log);
        }

        if (failed || a === 'AUTOMATION_RULE_FAILED') return { text, note: trimDot(note) ? String(note).trim() : '', result: 'Failed', tone: 'bad' };
        if (sent) return { text, note: '', result: 'Sent', tone: 'wait' };
        return { text, note: a === 'OPERATION_COMPLETED' ? note : '', result: 'Done', tone: 'ok' };
    }

    /** Who did it: the person, a rule's run as 'Schedule "Nightly batch"' / 'Rule "On lock"', or the platform. */
    function who(log) {
        if (RUN_ACTIONS.includes(log.action)) {
            const rule = /lock acquired|access approved/.test(log.details || '') ? 'Rule' : 'Schedule';
            return `${rule} "${log.targetName || ''}"`;
        }
        return log.userDisplayName || log.userEmail || 'The platform';
    }

    return { what, who, hoursText, plural, personName, skipReason };
})();

if (typeof window !== 'undefined') window.AuditText = AuditText;
