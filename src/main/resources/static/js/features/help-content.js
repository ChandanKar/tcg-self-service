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
                        'Open My Account (user menu) to see your access, cost, notification choices and profile; My activity lists what you did.'
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
            group: 'using',
            title: 'Activity and audit logs',
            roles: null,
            keywords: ['activity', 'audit', 'log', 'history', 'who', 'export'],
            sections: [
                {
                    heading: 'What is recorded',
                    paragraphs: [
                        'Every start, stop, access change, lock and setting change is recorded with who did it and when.',
                        'My activity lists what you did, as sentences by day, and exports it to CSV; All Logs (environment admins and admins) shows everything with filters and export.'
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
            group: 'access',
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
            group: 'manage',
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
                        'In an environment with leases, a start runs it for a set time. The banner on the environment shows when it will stop and who is keeping it running.',
                        'The default length and the longest allowed lease are set per environment; the banner shows both before you start.'
                    ]
                },
                {
                    heading: 'Keeping it running longer',
                    steps: [
                        'Click Extend on the lease banner and choose how much longer.',
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
                        'Open the environment and pick Groups.',
                        'Choose Add VM on a group and fetch the instances of the region.',
                        'Pick an instance by its name and save. Instance IDs are shown to administrators only.'
                    ]
                },
                {
                    heading: 'Needs attention',
                    paragraphs: [
                        'VMs found by discovery, changed outside the platform or no longer found in AWS are listed under Needs attention until someone marks them as reviewed or moves them.'
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
                        'A team can have a monthly limit on running hours or dollars. Going over can warn you, need an approval, or be refused, as the quota says. Your teams\' usage is in My Account > Cost.'
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
                        'Feature switches and thresholds take effect within 30 seconds on every server, without a restart. Changes are recorded in the audit log.',
                        'Settings marked Restart required are shown so you see the whole picture; change them in the server configuration and restart.',
                        'Reset to default returns a setting to the value in the server configuration.'
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
        'vm-registry': 'vm-registry',
        'automation-rules': 'automation-rules',
        'approval-policies': 'approvals',
        'budgets': 'budgets',
        'audit-logs-all': 'activity-logs',
        'cost-management': 'costs',
        'savings': 'savings',
        'waste': 'costs',
        'chargeback': 'chargeback',
        'cost-setup': 'costs',
        'user-management': 'getting-started',
        'teams': 'teams',
        'aws-accounts': 'getting-started',
        'api-access': 'getting-started',
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
