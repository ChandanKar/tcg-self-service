/**
 * In-app help topics (E29-T07). Plain text only: help.js renders every string escaped.
 * roles: null for everyone, or the roles that see the topic ('ADMIN', 'ENV_ADMIN').
 * optional: the feature can be switched off, so the topic says it may not be available (E29-T08).
 * group: where the topic is listed (GROUPS: using, access, cost, manage, admin; U09-T07).
 * legend: the topic shows the power-strip legend.
 * PAGE_TOPICS maps a page's help button to its topic; every id there must exist below.
 */
const HelpContent = (function() {
    'use strict';

    const TOPICS = [
        {
            id: 'getting-started',
            group: 'using',
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
                        'Everyone signs in as a User, an Environment admin or an Administrator.',
                        'On each environment you have one level: View only (see its VMs, IP addresses, cost and history), Operator (also start and stop VMs and take locks) or Admin (also give access and edit VMs, schedules and budgets).',
                        'Environment admins look after the environments they administer: access requests, schedules, the VM registry and budgets (Manage in the side menu).',
                        'Administrators do everything, across all environments, including cost, settings and AWS accounts (Cost and Administration).'
                    ]
                },
                {
                    heading: 'Your first steps',
                    steps: [
                        'Open Environments in the side menu to see the environments you can use.',
                        'If one you need is missing, open Request access and ask for it.',
                        'Your avatar (top right) opens My account: your access, cost, notification choices and profile. My activity lists what you did.'
                    ]
                }
            ]
        },
        {
            id: 'access',
            group: 'access',
            title: 'Requesting and managing access',
            roles: null,
            keywords: ['request', 'access', 'approve', 'expire', 'extend', 'grant', 'group'],
            sections: [
                {
                    heading: 'Asking for access',
                    steps: [
                        'Open Request access and pick the environment on the Request tab: the whole environment or one group in it.',
                        'Pick Operator or View only, and for how long: 7, 30 or 90 days.',
                        'Say why. The environment\'s admins get the request and decide; the answer shows under Your requests and in the bell.'
                    ]
                },
                {
                    heading: 'When access ends',
                    paragraphs: [
                        'You are warned before your access ends. Ask for more time from My account › Access while it is still active.',
                        'Your requests on Request access lists what you asked for and the answers; Request again asks for the same part and level.'
                    ]
                },
                {
                    heading: 'For approvers',
                    paragraphs: [
                        'Waiting requests are on Access requests (Manage), oldest first, and in the bell. Approve for another length or with no end, or Deny… with a reason the person sees.'
                    ]
                }
            ]
        },
        {
            id: 'environments',
            group: 'using',
            legend: true,
            title: 'Environments and start order',
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
            group: 'using',
            legend: true,
            title: 'Starting, stopping and locks',
            roles: null,
            keywords: ['start', 'stop', 'restart', 'lock', 'break lock', 'operation', 'stuck', 'cancel'],
            sections: [
                {
                    heading: 'Starting and stopping',
                    steps: [
                        'Open the environment and choose Start or Stop for the whole environment, a group or one VM. The confirm shows what will run and what it costs.',
                        'Follow it group by group in the progress dialog; Hide keeps it running and the banner at the top follows it.',
                        'If a VM fails, the result names it and why, with Retry for that VM and See steps. History on the environment page lists every run.'
                    ]
                },
                {
                    heading: 'Locks',
                    paragraphs: [
                        'Lock an environment while you work on it (Lock on the environment page): only you can start or stop it until you release the lock or it ends.',
                        'An admin can break a lock; the holder is told who broke it and why.'
                    ]
                }
            ]
        },
        {
            id: 'activity-logs',
            group: 'using',
            title: 'Activity and audit logs',
            roles: null,
            keywords: ['activity', 'audit', 'log', 'history', 'who', 'export'],
            sections: [
                {
                    heading: 'What is recorded',
                    paragraphs: [
                        'Every start, stop, access change, lock and setting change is recorded with who did it and when.',
                        'My activity lists what you did, as sentences by day, and exports it to CSV; the Audit log (Manage, for environment admins and admins) shows everyone\'s, with filters and export.'
                    ]
                }
            ]
        },
        {
            id: 'costs',
            group: 'cost',
            title: 'Costs and rightsizing',
            roles: null,
            keywords: ['cost', 'spend', 'estimate', 'actual', 'rightsizing', 'idle', 'forecast', 'commitment'],
            sections: [
                {
                    heading: 'Where costs come from',
                    paragraphs: [
                        'Estimates come from how long each VM ran and a price list; actual costs, when ingested, come from AWS Cost Explorer and replace the estimate for that day.',
                        'Your environments\' month to date is in My account › Cost and on each environment page.',
                        'Administrators see the whole fleet in Cost management (Overview, Rightsizing, Commitments, Carbon, Unusual spend and Setup); Waste lists what costs money and does nothing.'
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
            group: 'access',
            title: 'Notifications and preferences',
            roles: null,
            keywords: ['notification', 'bell', 'email', 'preferences', 'mute', 'turn off'],
            sections: [
                {
                    heading: 'Choosing what reaches you',
                    steps: [
                        'Open My account › Notifications, or Notification settings at the bottom of the bell.',
                        'Tick each event for In app and Email, then Save.'
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
            group: 'manage',
            title: 'Automation rules administration',
            roles: ['ENV_ADMIN'],
            keywords: ['automation', 'schedule', 'calendar', 'holiday', 'override', 'rule'],
            sections: [
                {
                    heading: 'Schedules',
                    steps: [
                        'Open Automation rules and choose New schedule, for an environment or one group in it.',
                        'Pick the days and the start and stop times (Advanced: different times per day); outside them it is stopped. This week shows the result.',
                        'Use holiday calendars and one-off overrides, such as Skip the next stop, for exceptions.'
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
            id: 'leases',
            group: 'using',
            title: 'Leases and extensions',
            roles: null,
            optional: true,
            keywords: ['lease', 'extend', 'extension', 'running until', 'auto stop', 'expire'],
            sections: [
                {
                    heading: 'What a lease is',
                    paragraphs: [
                        'In an environment with leases, a start runs it for a set time. The Lease line on the environment page shows when it stops and who keeps it running.',
                        'The default length and the longest allowed lease are set per environment; the start confirm shows both before you start.'
                    ]
                },
                {
                    heading: 'Keeping it running longer',
                    steps: [
                        'Choose Extend on the Lease line and how much longer.',
                        'Up to the environment\'s maximum the extension applies at once; beyond it, or when a policy requires it, an environment admin is asked to approve.',
                        'You are warned before the lease ends, in the bell and by email if you chose email.'
                    ]
                }
            ]
        },
        {
            id: 'idle-stop',
            group: 'using',
            title: 'Idle auto-stop and snooze',
            roles: null,
            optional: true,
            keywords: ['idle', 'auto-stop', 'snooze', 'unused', 'cpu', 'stopped automatically'],
            sections: [
                {
                    heading: 'When a VM is stopped for being idle',
                    paragraphs: [
                        'With idle auto-stop on for an environment, running VMs whose CPU, network and disk stay below the thresholds for long enough are stopped. Production environments are never stopped this way.',
                        'The environment page shows how long it has been idle and what stopping it saved.'
                    ]
                },
                {
                    heading: 'Keeping a quiet VM running',
                    steps: [
                        'Snooze idle auto-stop on the environment page (or from the Teams message) for the time you need.',
                        'Ask an environment admin to change the thresholds if the VM is often quiet but in use.'
                    ]
                }
            ]
        },
        {
            id: 'savings',
            group: 'admin',
            title: 'Savings and efficiency score',
            roles: ['ADMIN'],
            optional: false,
            keywords: ['savings', 'saved', 'efficiency', 'ledger', 'avoided hours', 'commitment'],
            sections: [
                {
                    heading: 'How savings are counted',
                    paragraphs: [
                        'Every hour a VM was stopped by a schedule, a lease, idle auto-stop or by hand is an avoided hour, priced at its on-demand rate. Hours already paid for by Savings Plans or Reserved Instances do not count as savings.',
                        'Storage keeps costing while a VM is stopped; it is subtracted, so the figure is a net saving.'
                    ]
                },
                {
                    heading: 'Efficiency score',
                    paragraphs: [
                        'The score compares the hours environments ran with the hours they were actually used. Higher is better; it falls when VMs run idle.'
                    ]
                }
            ]
        },
        {
            id: 'budgets',
            group: 'manage',
            title: 'Budgets and alerts',
            roles: ['ENV_ADMIN'],
            optional: false,
            keywords: ['budget', 'alert', 'threshold', 'forecast', 'freeze', 'overspend'],
            sections: [
                {
                    heading: 'Setting a budget',
                    steps: [
                        'Open Budgets and create one for an environment or a team, with a monthly amount.',
                        'Choose the thresholds that warn you (for example 50, 80 and 100 per cent).',
                        'Optionally choose what happens when it is spent: a warning only, holding new starts, or a stop that the owners approve.'
                    ]
                },
                {
                    heading: 'Alerts',
                    paragraphs: [
                        'Owners and admins are told when spend crosses a threshold and when the month-end forecast goes over the budget. A frozen environment shows a banner until the freeze ends.'
                    ]
                }
            ]
        },
        {
            id: 'chargeback',
            group: 'admin',
            title: 'Teams and chargeback',
            roles: ['ADMIN'],
            optional: true,
            keywords: ['team', 'chargeback', 'allocation', 'split', 'showback', 'finance', 'statement'],
            sections: [
                {
                    heading: 'Teams',
                    paragraphs: [
                        'Each environment can belong to a team. Teams carry members, quotas and budgets, and cost is reported per team.'
                    ]
                },
                {
                    heading: 'Chargeback',
                    paragraphs: [
                        'Chargeback shows each team\'s cost for a month: actual cost where AWS has it, otherwise the estimate. Shared costs are split by the split rules (evenly, by fixed percentages or in proportion to direct cost).',
                        'At the start of a month the previous month is finalised and can be sent to finance; a finalised month no longer changes.'
                    ]
                }
            ]
        },
        {
            id: 'teams',
            group: 'admin',
            title: 'Teams',
            roles: ['ADMIN'],
            optional: false,
            keywords: ['team', 'members', 'entra', 'group', 'cost centre', 'quota', 'limit'],
            sections: [
                {
                    heading: 'What a team is',
                    paragraphs: [
                        'A team is a set of people and the environments they own. Cost, budgets and monthly limits can be set per team, and chargeback reports cost per team.'
                    ]
                },
                {
                    heading: 'Members',
                    paragraphs: [
                        'Add people by hand, or link a Microsoft Entra group so its members are kept in step. People who come from the Entra group are removed there, not here.'
                    ]
                },
                {
                    heading: 'Monthly limits',
                    paragraphs: [
                        'A limit on VM hours or cost can warn, ask an owner to approve each start, or block new starts once it is reached. It starts again on the 1st of each month.'
                    ]
                }
            ]
        },
        {
            id: 'vm-registry',
            group: 'manage',
            title: 'VM registry',
            roles: ['ENV_ADMIN', 'ADMIN'],
            optional: false,
            keywords: ['registry', 'register', 'add vm', 'group', 'start order', 'move', 'remove', 'discovery', 'review'],
            sections: [
                {
                    heading: 'Environments, groups and VMs',
                    paragraphs: [
                        'An environment holds groups, and groups hold VMs. Groups start in their start order, and a group can wait for others to be running first. Stopping happens in reverse order.'
                    ]
                },
                {
                    heading: 'Adding VMs',
                    steps: [
                        'Open VM registry (Manage) and pick the environment.',
                        'Choose Add VMs from AWS, or Add VMs on a group.',
                        'Tick the instances by name and add them to a group. Instance IDs are shown to administrators only.'
                    ]
                },
                {
                    heading: 'Needs review',
                    paragraphs: [
                        'VMs found by discovery, changed outside the platform or no longer found in AWS are listed under Needs review (the registry\'s banner says Review them) until someone marks them as reviewed or moves them.'
                    ]
                }
            ]
        },
        {
            id: 'chat-actions',
            group: 'cost',
            title: 'Microsoft Teams actions and Jira tickets',
            roles: null,
            optional: true,
            keywords: ['teams', 'chat', 'channel', 'button', 'jira', 'ticket', 'approve from teams'],
            sections: [
                {
                    heading: 'Messages in Teams',
                    paragraphs: [
                        'Environment admins can send an environment\'s notifications to a Microsoft Teams channel. Some messages carry buttons, such as Approve, Extend lease or Snooze idle stop.'
                    ]
                },
                {
                    heading: 'Using a button',
                    steps: [
                        'Click the button in Teams; it opens this platform.',
                        'Sign in if asked and confirm. The action runs as you, with your access, and each link works once.'
                    ]
                },
                {
                    heading: 'Jira tickets',
                    paragraphs: [
                        'Where Jira is connected, idle VMs, rightsizing suggestions and cost anomalies can be turned into a Jira ticket; the ticket key then shows next to the item.'
                    ]
                }
            ]
        },
        {
            id: 'approvals',
            group: 'access',
            title: 'Approvals and quotas',
            roles: null,
            optional: true,
            keywords: ['approval', 'approve', 'policy', 'quota', 'held', 'waiting', 'cost preview'],
            sections: [
                {
                    heading: 'When a start needs approval',
                    paragraphs: [
                        'An approval policy can hold starts of large or expensive VMs, long runs, or starts above a cost. The start dialog shows the estimated cost before you confirm.',
                        'A held start waits on the environment page and in Approvals until an approver decides; once approved it runs as you.'
                    ]
                },
                {
                    heading: 'Team quotas',
                    paragraphs: [
                        'A team can have a monthly limit on running hours or dollars. Going over can warn you, need an approval, or be refused, as the quota says. Your teams\' usage is in My account › Cost.'
                    ]
                },
                {
                    heading: 'For approvers',
                    steps: [
                        'Open Approvals (or the bell or the Teams message).',
                        'Check the estimate and the reason, then approve or deny with a reason.'
                    ]
                }
            ]
        },
        {
            id: 'settings',
            group: 'admin',
            title: 'Settings',
            roles: ['ADMIN'],
            optional: false,
            keywords: ['settings', 'configuration', 'toggle', 'threshold', 'restart', 'flag'],
            sections: [
                {
                    heading: 'What you can change here',
                    paragraphs: [
                        'Feature switches and thresholds take effect within 30 seconds on every server, without a restart. Save changes applies them; each change is recorded in the audit log.',
                        'Rows marked Set in .env are read at start-up: they are shown so you see the whole picture; change them in the server configuration and restart.'
                    ]
                }
            ]
        },
        {
            id: 'api-access',
            group: 'admin',
            title: 'API access',
            roles: ['ADMIN'],
            optional: false,
            keywords: ['api', 'token', 'bearer', 'service account', 'ci', 'pipeline', 'script', 'swagger', 'webhook'],
            sections: [
                {
                    heading: 'Tokens',
                    paragraphs: [
                        'A token lets a script call the API as the person who made it: send it as Authorization: Bearer vmc_… . It can never do more than that person can, and only what its scopes allow.',
                        'A token is shown once, when it is made. Revoke it on API access if it may have leaked; it stops working at once.'
                    ]
                },
                {
                    heading: 'Service accounts',
                    paragraphs: [
                        'For pipelines and tools that should not act as a person. Each has an owner, a role (user or environment admin) and gets environments in Access management like anyone else.',
                        'Switching a service account off revokes its tokens. Switching it on again does not bring them back: make a new token.'
                    ]
                },
                {
                    heading: 'Switching tokens on',
                    paragraphs: [
                        'Tokens are off until the server is configured: set the token secret API_TOKEN_PEPPER (a long random value, kept secret), then API_TOKENS_ENABLED=true, and restart.',
                        'The API reference (every endpoint and its fields) is at /swagger-ui.html on the server when API_DOCS_ENABLED is on.'
                    ]
                }
            ]
        },
        {
            id: 'faq',
            group: 'cost',
            title: 'FAQ and troubleshooting',
            roles: null,
            keywords: ['403', 'forbidden', 'stuck', 'missing', 'error', 'not found', 'help'],
            sections: [
                {
                    heading: 'I get "access denied"',
                    paragraphs: [
                        'Your access may have ended or not cover that environment or group. Check My account › Access and ask for what you need on Request access.'
                    ]
                },
                {
                    heading: 'An operation looks stuck',
                    paragraphs: [
                        'Large VMs can take several minutes to pass their checks. If nothing moves for a long time, choose Cancel the rest in the progress dialog and try again; state sync corrects the status within minutes.'
                    ]
                },
                {
                    heading: 'A VM is missing',
                    paragraphs: [
                        'It may be in another group, inactive, or not added yet. Environment admins can find it in the VM registry under Needs review.'
                    ]
                }
            ]
        }
    ];

    /**
     * Help topic of each page, keyed by the router's page name (U02-T04), plus panels with their own
     * "?" (leases, idle-stop, my-account).
     */
    const PAGE_TOPICS = {
        'dashboard': 'getting-started',
        'my-environments': 'environments',
        'environment-detail': 'environments',
        'request-access': 'access',
        'approvals': 'approvals',
        'activity-logs': 'activity-logs',
        'templates': 'getting-started',
        'help': 'getting-started',
        'chat-action': 'chat-actions',
        'pending-requests': 'access',
        'access-management': 'access',
        'access-grant': 'access',
        'vm-registry': 'vm-registry',
        'register-vms': 'vm-registry',
        'environment-settings': 'vm-registry',
        'automation-rules': 'automation-rules',
        'automation-rule-new': 'automation-rules',
        'automation-rule-edit': 'automation-rules',
        'approval-policies': 'approvals',
        'budgets': 'budgets',
        'audit-logs-all': 'activity-logs',
        'cost-management': 'costs',
        'savings': 'savings',
        'waste': 'costs',
        'waste-fixes': 'costs',
        'chargeback': 'chargeback',
        'cost-setup': 'costs',
        'user-management': 'getting-started',
        'user-management-add': 'getting-started',
        'teams': 'teams',
        'team-detail': 'teams',
        'aws-accounts': 'getting-started',
        'api-access': 'api-access',
        'settings': 'settings',
        'system-health': 'getting-started',
        // panels
        'leases': 'leases',
        'idle-stop': 'idle-stop',
        'my-account': 'notifications'
    };

    function all() {
        return TOPICS;
    }

    function find(id) {
        return TOPICS.find(t => t.id === id) || null;
    }

    /** The Help page's groups, in order (Help.dc.html). */
    const GROUPS = [['using', 'Using environments'], ['access', 'Access'], ['cost', 'Cost and more'],
        ['manage', 'Manage'], ['admin', 'Administration']];

    return { all, find, PAGE_TOPICS, GROUPS };
})();

window.HelpContent = HelpContent;
