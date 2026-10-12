/**
 * In-app help topics (E29-T07). Plain text only: help.js renders every string escaped.
 * roles: null for everyone, or the roles that see the topic ('ADMIN', 'ENV_ADMIN').
 * PAGE_TOPICS maps a page's help button to its topic; every id there must exist below.
 */
const HelpContent = (function() {
    'use strict';

    const TOPICS = [
        {
            id: 'getting-started',
            title: 'Getting started and roles',
            roles: null,
            keywords: ['roles', 'viewer', 'user', 'admin', 'environment admin', 'sign in', 'start'],
            sections: [
                {
                    heading: 'What this platform does',
                    paragraphs: [
                        'You start and stop the cloud environments you work with, see what they cost, and ask for access to the ones you need. Every action is recorded.'
                    ]
                },
                {
                    heading: 'Roles',
                    paragraphs: [
                        'Viewer: sees environments, their VMs and schedules.',
                        'User: everything a viewer can do, plus start and stop VMs in environments they have access to.',
                        'Environment admin: manages access, schedules and settings for the environments they administer.',
                        'Admin: everything, across all environments, including costs, settings and AWS accounts.'
                    ]
                },
                {
                    heading: 'Your first steps',
                    steps: [
                        'Open Environments to see the environments you can use.',
                        'If one you need is missing, open Request Access and ask for it.',
                        'Open My Account (user menu) to see your access, activity, cost and notification choices.'
                    ]
                }
            ]
        },
        {
            id: 'access',
            title: 'Requesting and managing access',
            roles: null,
            keywords: ['request', 'access', 'approve', 'expire', 'extend', 'grant', 'group'],
            sections: [
                {
                    heading: 'Asking for access',
                    steps: [
                        'Open Request Access and choose the environment, or a single group inside it.',
                        'Pick the level you need (View only, Start and stop, Manage access) and how long you need it.',
                        'Explain why. The environment\'s admins are told and decide.'
                    ]
                },
                {
                    heading: 'When access ends',
                    paragraphs: [
                        'You are warned before your access expires. Ask for an extension from My Account > Access while it is still active.',
                        'Ended access is listed in My Account, where you can ask for it again with the same scope.'
                    ]
                },
                {
                    heading: 'For approvers',
                    paragraphs: [
                        'Pending requests appear under Pending Requests and in the bell. Approving can shorten the duration or grant without an end date.'
                    ]
                }
            ]
        },
        {
            id: 'environments',
            title: 'Environments, groups and start order',
            roles: null,
            keywords: ['environment', 'group', 'order', 'sequence', 'dependency', 'eks', 'node group', 'region'],
            sections: [
                {
                    heading: 'How an environment is organised',
                    paragraphs: [
                        'An environment holds groups, and groups hold VMs (or EKS node groups). Groups start in their order; VMs inside a group start in their sequence.',
                        'A VM can depend on others: it starts after them and stops before them.'
                    ]
                },
                {
                    heading: 'Status',
                    paragraphs: [
                        'Statuses come from the cloud and are refreshed every few minutes. Drift means the cloud state changed outside the platform; it is recorded and shown on the VM.'
                    ]
                }
            ]
        },
        {
            id: 'locks',
            title: 'Starting, stopping and locks',
            roles: null,
            keywords: ['start', 'stop', 'restart', 'lock', 'break lock', 'operation', 'stuck', 'cancel'],
            sections: [
                {
                    heading: 'Starting and stopping',
                    steps: [
                        'Open the environment and choose Start, Stop or Restart for the environment, a group or a single VM.',
                        'Follow the progress in the operation panel; each step shows its own status.',
                        'A failed step stops the operation and tells you why; the rest of the environment is left as it was.'
                    ]
                },
                {
                    heading: 'Locks',
                    paragraphs: [
                        'Lock an environment while you work on it: only you can start or stop it until you release the lock or it expires.',
                        'An admin can break a lock; the holder is told who broke it and why.'
                    ]
                }
            ]
        },
        {
            id: 'activity-logs',
            title: 'Activity and audit logs',
            roles: null,
            keywords: ['activity', 'audit', 'log', 'history', 'who', 'export'],
            sections: [
                {
                    heading: 'What is recorded',
                    paragraphs: [
                        'Every start, stop, access change, lock and setting change is recorded with who did it and when.',
                        'Activity Logs shows the environments you can see; All Logs (environment admins and admins) shows everything with filters and export.'
                    ]
                }
            ]
        },
        {
            id: 'costs',
            title: 'Costs and rightsizing',
            roles: null,
            keywords: ['cost', 'spend', 'estimate', 'actual', 'rightsizing', 'idle', 'forecast', 'commitment'],
            sections: [
                {
                    heading: 'Where costs come from',
                    paragraphs: [
                        'Estimates come from how long each VM ran and a price list; actual costs, when ingested, come from AWS Cost Explorer and replace the estimate for that day.',
                        'Your environments\' month to date is in My Account > Cost and on each environment page.'
                    ]
                },
                {
                    heading: 'Rightsizing and idle VMs',
                    paragraphs: [
                        'A VM that stays far below its capacity is suggested a smaller type; one that runs hot is suggested a larger one. Idle VMs are ones that ran without real use.'
                    ]
                }
            ]
        },
        {
            id: 'notifications',
            title: 'Notifications and preferences',
            roles: null,
            keywords: ['notification', 'bell', 'email', 'preferences', 'mute', 'turn off'],
            sections: [
                {
                    heading: 'Choosing what reaches you',
                    steps: [
                        'Open My Account > Notifications, or Notification settings at the bottom of the bell.',
                        'Turn each event on or off for the bell and for email, then Save.'
                    ]
                },
                {
                    heading: 'What you cannot turn off',
                    paragraphs: [
                        'A few bell notifications are required, such as your access ending or your lock being broken. Email is only sent for events your organisation emails.'
                    ]
                }
            ]
        },
        {
            id: 'automation-rules',
            title: 'Automation rules administration',
            roles: ['ENV_ADMIN'],
            keywords: ['automation', 'schedule', 'calendar', 'holiday', 'override', 'rule'],
            sections: [
                {
                    heading: 'Schedules',
                    steps: [
                        'Open Automation Rules and create a rule for an environment or group.',
                        'Add the windows when it should run; outside them it is stopped.',
                        'Use holiday calendars and one-off overrides for exceptions.'
                    ]
                },
                {
                    heading: 'When a rule is skipped',
                    paragraphs: [
                        'A rule does not act on a locked environment or one with an operation running; its owner is told why.'
                    ]
                }
            ]
        },
        {
            id: 'faq',
            title: 'FAQ and troubleshooting',
            roles: null,
            keywords: ['403', 'forbidden', 'stuck', 'missing', 'error', 'not found', 'help'],
            sections: [
                {
                    heading: 'I get "access denied"',
                    paragraphs: [
                        'Your access may have ended or not cover that environment or group. Check My Account > Access and request what you need.'
                    ]
                },
                {
                    heading: 'An operation looks stuck',
                    paragraphs: [
                        'Large VMs can take several minutes to pass their checks. If nothing moves for a long time, cancel it from the operation panel and try again; state sync corrects the status within minutes.'
                    ]
                },
                {
                    heading: 'A VM is missing',
                    paragraphs: [
                        'It may be in another group, inactive, or not registered yet. Environment admins can find it in the VM registry under Needs attention.'
                    ]
                }
            ]
        }
    ];

    /** Page help buttons -> topic ids. */
    const PAGE_TOPICS = {
        environments: 'locks',
        'environment-detail': 'locks',
        'activity-logs': 'activity-logs',
        'all-logs': 'activity-logs',
        'cost-management': 'costs',
        'request-access': 'access',
        'my-account': 'notifications',
        'vm-registry': 'environments',
        'automation-rules': 'automation-rules'
    };

    function all() {
        return TOPICS;
    }

    function find(id) {
        return TOPICS.find(t => t.id === id) || null;
    }

    return { all, find, PAGE_TOPICS };
})();

window.HelpContent = HelpContent;
