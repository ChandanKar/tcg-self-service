/**
 * In-app help topics (E29-T07, rewritten for every persona in U27). Plain text only: help.js renders
 * every string escaped. Topics are written as tasks: what to press, what you see, what it means.
 *   roles:    null for everyone, or the roles that see the topic ('ADMIN', 'ENV_ADMIN').
 *   optional: the feature can be switched off, so the topic says it may not be available (E29-T08).
 *   feature:  the Capabilities switch; the topic is hidden while it is off (U23-T13).
 *   group:    where the topic is listed (GROUPS; U09-T07).
 *   legend:   the topic shows the power-strip legend.
 *   summary:  one line under the title. related: topic ids linked at the end.
 *   sections: { heading, paragraphs?, steps?, terms? } where terms are [what you see, what it means].
 * PAGE_TOPICS maps a page's help button to its topic; every id there must exist below.
 * Labels are quoted as the screens show them; keep them in step when a screen's wording changes.
 */
const HelpContent = (function() {
    'use strict';

    const ASK_ADMIN = "Stuck? Ask your platform administrator. Environment admins can help with access to their environments.";

    const TOPICS = [
        // ------------------------------------------------------------------ Using environments
        {
            id: 'getting-started',
            group: 'using',
            title: "Getting started and roles",
            roles: null,
            summary: "Start and stop the cloud environments you work with, see what they cost, and ask for access to the ones you need. Every action is recorded.",
            keywords: ['roles', 'viewer', 'view only', 'operator', 'admin', 'environment admin', 'administrator', 'menu', 'first steps', 'who to ask', 'support'],
            sections: [
                {
                    heading: "Your access on each environment",
                    paragraphs: ["You have one level on each environment you can use, for the whole environment or only some of its groups:"],
                    terms: [
                        ["View only", "See its VMs, IP addresses, state, cost and history."],
                        ["Operator", "Also start and stop VMs, and lock the environment while you work on it."],
                        ["Admin", "Also give access, and edit its VMs, schedules and budgets."],
                        ["Some groups", "Your level covers only the groups named; the buttons show only on those groups."]
                    ]
                },
                {
                    heading: "Your role on the platform",
                    terms: [
                        ["User", "Uses the environments they have been given access to. Sees Workspace in the side menu."],
                        ["Environment admin", "Also decides access requests, gives access and manages VMs, schedules and budgets for the environments they look after. Sees Manage."],
                        ["Administrator", "Everything, for every environment, including users, AWS accounts, cost and settings. Sees Cost and Administration."]
                    ]
                },
                {
                    heading: "Your first steps",
                    steps: [
                        "Open Home: it shows your environments, what needs you, and your recent activity.",
                        "Open Environments to see every environment you can use, then open one to start, stop or lock it.",
                        "Missing one? Open Request access, pick it, choose Operator or View only, say why and press “Send request”.",
                        "Your avatar (top right) › “My account” shows your access, cost, notification choices and profile."
                    ]
                },
                {
                    heading: "Where to get help",
                    paragraphs: [
                        "Every page has a “?” in its header that opens its help topic; the “?” in the top bar also lists all topics and the keyboard shortcuts.",
                        ASK_ADMIN
                    ]
                }
            ],
            related: ['sign-in', 'home', 'environments', 'access']
        },
        {
            id: 'sign-in',
            group: 'using',
            title: "Signing in and out",
            roles: null,
            summary: "Sign in with your Microsoft account, or with the username and password an administrator gave you.",
            keywords: ['sign in', 'login', 'log in', 'password', 'forgot', 'reset', 'microsoft', 'username', 'signed out', 'session', 'expired', 'first sign-in'],
            sections: [
                {
                    heading: "Signing in",
                    steps: [
                        "Press the Microsoft button and sign in with your work account. That is all for most people.",
                        "Given a username and password instead? Type them under “or with a username” and press “Sign in”."
                    ]
                },
                {
                    heading: "Your first sign-in with a password",
                    steps: [
                        "Sign in with the password you were given. It works once.",
                        "The page asks you to “Choose your password”: 12 to 72 characters.",
                        "Type it in “New password” and again in “The same again”, then press “Save and sign in”."
                    ]
                },
                {
                    heading: "Forgot your password?",
                    paragraphs: [
                        "There is no reset link and nothing is emailed. Ask your platform administrator: they reset it, give you a new one-time password, and you choose your own at the next sign-in. A reset also signs you out everywhere."
                    ]
                },
                {
                    heading: "Signing out",
                    paragraphs: [
                        "Avatar (top right) › “Sign out”. On a phone it is in the menu sheet, and in My account › Profile.",
                        "After 60 minutes without activity you see “You've been signed out”. Press “Sign in again”: you come back to the page you were on."
                    ]
                },
                {
                    heading: "Messages you may see",
                    terms: [
                        ["Invalid username or password.", "Check both. Still failing? Your account may be deactivated: ask your platform administrator."],
                        ["Signing in with a username is switched off. Use Sign in with Microsoft.", "Your organisation allows Microsoft sign-in only."],
                        ["Signing in with Microsoft did not work.", "Try again; if it keeps happening, ask your platform administrator."],
                        ["Your session ended. Sign in again to carry on.", "You were away too long, or a password reset ended your session."]
                    ]
                }
            ],
            related: ['getting-started', 'faq']
        },
        {
            id: 'home',
            group: 'using',
            title: "Home",
            roles: null,
            summary: "What needs you now, your environments at a glance, and what you did last.",
            keywords: ['home', 'dashboard', 'needs you', 'your environments', 'recent activity', 'barely used', 'attention'],
            sections: [
                {
                    heading: "What Home shows",
                    terms: [
                        ["The line under Home", "How many of your VMs are running, across how many environments, and what they cost this month."],
                        ["Needs you", "Things with a deadline: an environment about to stop (“Extend lease”) or access about to end (“Ask for more time”)."],
                        ["Your environments", "Up to four cards: the ones you locked, then running ones. Each shows its lights, a status line and its cost. “See all as a list” opens Environments."],
                        ["Running but barely used", "Running VMs that have been nearly idle. Stopping them saves money; “Review” opens the environment."],
                        ["Your recent activity", "Your last four actions and how they ended. “All activity” opens My activity."]
                    ]
                },
                {
                    heading: "For administrators",
                    paragraphs: [
                        "Administrators see a fleet view instead: the running VMs and month's spend with its forecast, Needs attention tiles (access requests, approvals, failed operations, VMs to review, budgets, late jobs), Fleet right now and Spend this month. Each tile opens the page where it is dealt with."
                    ]
                },
                {
                    heading: "Nothing on Home?",
                    paragraphs: ["“You have no environments yet.” means you have no access yet. Press “Request access to an environment”."]
                }
            ],
            related: ['environments', 'access', 'leases']
        },
        {
            id: 'environments',
            group: 'using',
            legend: true,
            title: "Reading an environment",
            roles: null,
            summary: "Find an environment, then read what is running, who started it, and what is planned for it.",
            keywords: ['environment', 'list', 'group', 'start order', 'sequence', 'dependency', 'eks', 'node group', 'region', 'state', 'started by', 'outside the platform', 'drift', 'insights', 'history', 'details'],
            sections: [
                {
                    heading: "Finding an environment",
                    steps: [
                        "Open Environments in the side menu.",
                        "Search by name, use Show (All, Running, Stopped) and the order (By name, Most running, Highest cost).",
                        "Each row shows its schedule, lights, lock, your access and its cost this month. “Export CSV” downloads what matches. Press “Open” to see one."
                    ]
                },
                {
                    heading: "How an environment is organised",
                    paragraphs: [
                        "An environment holds groups, and groups hold VMs (or EKS node groups). The Start order strip at the top shows the groups in order: they start left to right and stop right to left. A group can wait for another to be running first."
                    ]
                },
                {
                    heading: "What the VM table tells you",
                    terms: [
                        ["Running since 08:02", "Running since that time. Hover the state to see who started it: “Started by Asha at 09:12”, “Started by the schedule …” or “Started outside the platform”."],
                        ["Started outside the platform", "Someone started or stopped it directly in AWS, not here. The platform records it as a change outside the platform."],
                        ["Failed to start", "The last start failed. Press “Retry” on the VM, or open “History” to see why."],
                        ["Not found in the cloud", "AWS no longer has this instance. Tell an environment admin."],
                        ["Unknown", "Not read from AWS yet; it updates on the next sync, within a few minutes."],
                        ["Start order 2 · after Database", "This group starts second, once Database is running."]
                    ]
                },
                {
                    heading: "The side panel",
                    terms: [
                        ["Lock", "Who has locked it, why and until when. See Locking an environment."],
                        ["Schedule", "The next scheduled stop and start, or “Started and stopped by hand”."],
                        ["Lease / Idle auto-stop", "Shown only when your organisation uses them."],
                        ["Cost this month", "The month to date, the change against the same days last month, the last 14 days, and where the month is heading."]
                    ]
                },
                {
                    heading: "More detail",
                    paragraphs: [
                        "“Insights” lists what to do (idle VMs, resizes), the busiest VMs and cost by group. “History” lists every start and stop, newest first, with who ran it and each step. “Details” on a VM shows its size, IPs, use, storage and its own history.",
                        "Banners at the top warn about out-of-date states (“VM states may be out of date.”), VMs changed outside the platform, and budgets that block starts."
                    ]
                }
            ],
            related: ['locks', 'locking', 'schedules', 'costs']
        },
        {
            id: 'locks',
            group: 'using',
            legend: true,
            title: "Starting and stopping",
            roles: null,
            summary: "Start or stop a whole environment, one group or one VM, and follow it to the end. Needs Operator access.",
            keywords: ['start', 'stop', 'restart', 'operation', 'progress', 'stuck', 'cancel', 'retry', 'failed', 'cost', 'confirm', 'scale'],
            sections: [
                {
                    heading: "Starting",
                    steps: [
                        "Open the environment. Press “Start all” (or “Start the 3 stopped”), “Start group” on a group, or “Start” on one VM.",
                        "Read the dialog, for example “Start 2 VMs in Payments Dev?”: what starts now, what is already running, and “It usually takes about 4 minutes.”",
                        "Check the Estimated cost and the Approval box (below).",
                        "Press “Start 2 VMs”. The progress dialog opens."
                    ]
                },
                {
                    heading: "What the start dialog means",
                    terms: [
                        ["They run until someone stops them", "Nothing stops it automatically. Stop it when you are done."],
                        ["They run until the next scheduled stop, Fri 12:30", "The environment's schedule stops it then, unless someone stops it sooner."],
                        ["Keep them running until", "Shown when your organisation uses leases: pick how long; you are reminded before it stops."],
                        ["$0.72 · for 8 h, $0.09 an hour", "The estimate for that length, at AWS list prices. “$0.09 an hour” alone means it runs until stopped."],
                        ["No approval needed", "It starts as soon as you confirm."],
                        ["Needs Sanjay Rao", "A policy needs someone's yes. The button reads “Send for approval”; it starts once approved."]
                    ]
                },
                {
                    heading: "Stopping",
                    steps: [
                        "Press “Stop all”, “Stop group” or “Stop” on a VM.",
                        "If someone else started those VMs, the dialog says so: “Someone else started these VMs. web-01: started by Sayan Hazra at 07:01.” Check with them first.",
                        "Press “Stop 2 VMs”. Groups stop in reverse order."
                    ]
                },
                {
                    heading: "Following it",
                    paragraphs: [
                        "The progress dialog shows each group and VM, a timer (“0:00 so far · usually about 4 min”) and a live line such as “3 of 6 running · Application is starting; Web waits for it”.",
                        "“Hide · keeps running” closes the dialog; the banner at the top follows it and “Details” opens it again. “Cancel the rest” stops what has not run yet; VMs already started stay started."
                    ]
                },
                {
                    heading: "How it ended",
                    terms: [
                        ["Done. Every VM finished.", "All done."],
                        ["Started 5 of 6 VMs. pay-api-02 didn't start: …", "Partly done. Press “Retry pay-api-02” or “See steps” for the reason."],
                        ["Some VMs were skipped", "A VM they wait for did not start, so they were not started."],
                        ["Another start or stop is still running here", "Wait for it to finish (the button reads “In progress”), then try again."],
                        ["Locked by Asha. You can see it but not start VMs.", "Someone holds the lock. Ask them, or wait for it to end."],
                        ["Reconnecting…", "The page lost touch for a moment; it keeps trying. The operation carries on in the cloud."],
                        ["Still not finished after 30 minutes", "Check History; tell your administrator if it is still running."]
                    ]
                },
                {
                    heading: "View only?",
                    paragraphs: ["You see “You can see this environment but not start or stop it.” Press “Ask for operator access” to request it."]
                }
            ],
            related: ['locking', 'schedules', 'approvals', 'faq']
        },
        {
            id: 'locking',
            group: 'using',
            title: "Locking an environment",
            roles: null,
            summary: "Lock an environment while you work on it, so nobody else starts or stops its VMs and schedules wait.",
            keywords: ['lock', 'unlock', 'release', 'extend', 'break lock', 'locked by', 'reason'],
            sections: [
                {
                    heading: "Taking a lock",
                    steps: [
                        "On the environment page, press “Lock” in the Lock panel.",
                        "Write why under “Why (others see this)”, for example “Data migration rehearsal”.",
                        "Pick how long under Until: “1 hour”, “2 hours”, “End of day” or “Until I release it”. If your organisation does not end locks automatically, “Until I release it” is the only choice.",
                        "Press “Lock environment”. The panel reads “Locked by you until 14:30”."
                    ]
                },
                {
                    heading: "Keeping it or letting go",
                    paragraphs: [
                        "“Extend” adds 30 min, 1 h or 2 h, counted from the current end time. You are warned 15 and 5 minutes before a timed lock ends.",
                        "“Release” ends it at once; others can start, stop and lock again."
                    ]
                },
                {
                    heading: "What others see",
                    paragraphs: [
                        "A red banner “Locked by Asha”, with since when, the reason and until when: “You can see it but not start or stop VMs.” Schedules do not break a lock; they retry, then skip and tell the schedule's owner.",
                        "Environment admins can “Break” a lock with a reason. The holder is told who broke it and why."
                    ]
                }
            ],
            related: ['locks', 'schedules']
        },
        {
            id: 'schedules',
            group: 'using',
            title: "Schedules: why it starts or stops on its own",
            roles: null,
            summary: "Many environments follow a weekly schedule, so they are off at night and at weekends.",
            keywords: ['schedule', 'stopped by itself', 'automatic', 'night', 'weekend', 'holiday', 'started by the schedule'],
            sections: [
                {
                    heading: "Seeing the schedule",
                    paragraphs: [
                        "The Schedule panel on the environment page shows the next stop and start, for example “Next stop Mon 20:00”. The Environments list has a Schedule column: “Stops 19:00” or “No schedule”."
                    ]
                },
                {
                    heading: "Working outside the schedule",
                    steps: [
                        "Start it as usual. The dialog tells you when the schedule will stop it.",
                        "Need it past the stop? Lock it: a schedule never stops a locked environment.",
                        "Need the schedule changed, or one stop skipped? Ask an environment admin (“Skip next stop”)."
                    ]
                },
                {
                    heading: "Other things that stop VMs",
                    paragraphs: [
                        "Where your organisation uses them, a lease stops an environment when its time is up, and idle auto-stop stops one whose VMs have been idle. History shows which one did it: “Schedule …”, “Lease” or “Idle auto-stop”."
                    ]
                }
            ],
            related: ['locking', 'leases', 'idle-stop', 'automation-rules']
        },
        {
            id: 'leases',
            group: 'using',
            title: "Leases: keeping it running longer",
            roles: null,
            optional: true,
            feature: 'leases', // U23-T13: hidden while leases are switched off
            summary: "In an environment with leases, every start runs for a set time; you are reminded before it stops and can extend it.",
            keywords: ['lease', 'extend', 'extension', 'running until', 'auto stop', 'expire', 'stops at'],
            sections: [
                {
                    heading: "How it works",
                    paragraphs: [
                        "The Lease panel shows “Runs 8 h when started” and “Up to 24 h without approval”. When you start, pick the length under “Keep them running until”.",
                        "While it runs, the panel reads “Stops at 20:00 · in 2 h 15 min”. You are reminded 60, 15 and 5 minutes before, and a gold banner shows in the last hour."
                    ]
                },
                {
                    heading: "Extending",
                    steps: [
                        "Press “Extend” on the Lease panel, the banner, Home (“Extend lease”) or My account › Access.",
                        "Pick 30 min to 12 h. The dialog shows the new stop time and what it costs.",
                        "Within the limit, press “Extend by 2 h”: it applies at once.",
                        "Beyond the limit, write a “Reason for the owner” and press “Ask the owner”. The stop time stays until they approve."
                    ]
                }
            ],
            related: ['locks', 'approvals']
        },
        {
            id: 'idle-stop',
            group: 'using',
            title: "Idle auto-stop and pausing it",
            roles: null,
            optional: true,
            feature: 'idleStop',
            summary: "An environment whose VMs stay idle for a while can be stopped automatically to save money.",
            keywords: ['idle', 'auto-stop', 'snooze', 'pause', 'unused', 'cpu', 'stopped automatically', 'dry run', 'working late'],
            sections: [
                {
                    heading: "What the panel says",
                    terms: [
                        ["Off", "Nothing is stopped for being idle."],
                        ["Dry run · after 60 min idle", "It only records what it would have stopped (“Would have saved $12 in the last 7 days”)."],
                        ["On · after 60 min idle", "When all its VMs have been idle that long, it is stopped in reverse start order."],
                        ["Production — excluded", "Production environments are never stopped this way."]
                    ]
                },
                {
                    heading: "Working late on a quiet VM",
                    steps: [
                        "Press “Pause or review” on the Idle auto-stop panel.",
                        "Under “Working late? Pause it for”, pick 2, 4 or 8 hours. Anyone who can start the environment can pause it.",
                        "“Resume now” switches it back on early."
                    ]
                }
            ],
            related: ['schedules', 'environment-settings']
        },
        {
            id: 'phone-shortcuts',
            group: 'using',
            title: "Phones and keyboard shortcuts",
            roles: null,
            summary: "Everything works on a phone, laid out for a small screen; on a computer, a few keys get you around faster.",
            keywords: ['phone', 'mobile', 'keyboard', 'shortcuts', 'keys'],
            sections: [
                {
                    heading: "On a phone",
                    paragraphs: [
                        "The bar at the bottom has Home, Environments, Activity and More (the full menu, Help and Sign out). An environment shows its state, a light strip per group and the Start and Stop buttons first; groups open to show their VMs. Wide tables become cards."
                    ]
                },
                {
                    heading: "Keyboard shortcuts",
                    terms: [
                        ["g d / g e / g r / g a", "Go to Home, Environments, Request access, My activity."],
                        ["r", "Refresh what you see."],
                        ["?", "Show the list of shortcuts."],
                        ["Esc", "Close a dialog or panel."]
                    ],
                    paragraphs: ["Shortcuts don't fire while you type in a field or while a dialog is open."]
                }
            ]
        },
        {
            id: 'templates',
            group: 'using',
            title: "New environments from templates",
            roles: null,
            summary: "Anyone can ask for a new environment built from a ready-made template; an administrator approves it before it is built.",
            optional: true,
            feature: 'templates',
            keywords: ['template', 'new environment', 'provision', 'create environment'],
            sections: [
                {
                    heading: "Asking for a new environment",
                    steps: [
                        "Open “New environment” in the side menu and the “Ask for one” tab.",
                        "Pick a template, fill in what it asks and send it. Administrators review it.",
                        "Follow it on “My requests”."
                    ]
                },
                {
                    heading: "For administrators",
                    paragraphs: ["“Templates” lists each template as Offered or Not offered, with its editor; “Requests” lists what is waiting for you to approve or decline."]
                }
            ]
        },

        // ------------------------------------------------------------------ Access and notifications
        {
            id: 'access',
            group: 'access',
            title: "Requesting access",
            roles: null,
            summary: "Ask for View only or Operator access to an environment, or to one of its groups, for 7, 30 or 90 days.",
            keywords: ['request', 'access', 'approve', 'expire', 'extend', 'grant', 'group', 'more time', 'operator access', 'declined'],
            sections: [
                {
                    heading: "Asking for access",
                    steps: [
                        "Open Request access (or press “Ask for operator access” on an environment you can only view).",
                        "Pick the environment in the list on the left.",
                        "Under “What you need”, choose Operator (start, stop and lock) or View only.",
                        "Under “Part of it”, choose the whole environment or one group.",
                        "Under “For”, choose 7, 30 or 90 days.",
                        "Write at least 10 characters under “Why you need it”, then press “Send request”."
                    ]
                },
                {
                    heading: "What happens next",
                    paragraphs: [
                        "The environment's admins get your request. Follow it on “Your requests”: Waiting (with “Cancel request”), Approved (“Open …”), or Declined with their reason (“Request again”). The answer also comes to the bell."
                    ]
                },
                {
                    heading: "When access is ending",
                    steps: [
                        "You are told before it ends, in the bell and on Home (“Your access to ISE ends Fri”).",
                        "Press “Ask for more time” (or My account › Access › “Request an extension”).",
                        "Pick how much longer, say why (at least 10 characters) and press “Send request”."
                    ]
                },
                {
                    heading: "Messages you may see",
                    terms: [
                        ["Say a little more: at least 10 characters.", "The reason is too short."],
                        ["You have this already", "You have View only already; ask for Operator."],
                        ["You can use every environment.", "There is nothing left to ask for."]
                    ]
                }
            ],
            related: ['my-account', 'notifications', 'access-admin']
        },
        {
            id: 'my-account',
            group: 'access',
            title: "My account",
            roles: null,
            summary: "Your access, your environments' cost, what reaches you, and your profile. Open it from your avatar (top right) › “My account”.",
            keywords: ['my account', 'profile', 'access', 'grants', 'extension', 'cost', 'notifications', 'api tokens', 'sign out'],
            sections: [
                {
                    heading: "The tabs",
                    terms: [
                        ["Access", "Running environments you can extend, requests waiting for a decision, and each grant with its level, end date and who granted it. “Request an extension” appears when one is ending."],
                        ["Cost", "Month to date across the environments you can use."],
                        ["Notifications", "What reaches you in the app and by email."],
                        ["Profile", "How you sign in, your role, team and previous sign-in, and “Sign out”."],
                        ["API tokens", "Shown when tokens are switched on: tokens for your scripts."]
                    ]
                }
            ],
            related: ['access', 'notifications']
        },
        {
            id: 'notifications',
            group: 'access',
            title: "The bell and notifications",
            roles: null,
            summary: "The bell (top right) lists what happened to your environments, access and requests.",
            keywords: ['notification', 'bell', 'email', 'preferences', 'mute', 'turn off', 'unread', 'mark all as read'],
            sections: [
                {
                    heading: "Using the bell",
                    paragraphs: [
                        "The number is how many are unread. Open the bell to see the 8 newest; a red dot marks a failure, gold something ending or waiting on you. Clicking one marks it read and opens its environment. “Mark all as read” clears the rest."
                    ]
                },
                {
                    heading: "What you are told about",
                    terms: [
                        ["Request approved / Request declined", "The answer to your access request, with the reason when declined."],
                        ["Access expiring / Access expired", "Your access is about to end, or has ended."],
                        ["New access / Access level changed / Access revoked", "An admin changed your access."],
                        ["Your start completed / Your start failed", "How your operation ended."],
                        ["Changed outside the platform", "Someone started or stopped VMs directly in AWS."],
                        ["Lock broken / Your lock expires at 14:30", "About your locks."],
                        ["Environment stopping soon / Lease expired", "About leases, where used."]
                    ]
                },
                {
                    heading: "Choosing what reaches you",
                    steps: [
                        "Open “Notification settings” at the bottom of the bell, or My account › Notifications.",
                        "Tick In app and Email for each event, then press “Save”."
                    ],
                    paragraphs: ["Events marked Always stay in the bell, such as your access ending or your lock being broken. “Not emailed” means your organisation does not email that event."]
                }
            ],
            related: ['my-account', 'chat-actions']
        },
        {
            id: 'approvals',
            group: 'access',
            title: "Approvals: starts that need a yes",
            roles: null,
            optional: true,
            feature: 'approvals',
            summary: "A policy can hold starts that run long, cost a lot or use large VMs until someone approves them.",
            keywords: ['approval', 'approve', 'policy', 'quota', 'held', 'waiting', 'send for approval', 'deny'],
            sections: [
                {
                    heading: "When your start needs approval",
                    steps: [
                        "The start dialog's Approval box names who decides (“Needs Sanjay Rao”) and why; the button reads “Send for approval”.",
                        "Press it. The environment page shows “Your start is waiting” with “Cancel request”.",
                        "When it is approved, it starts as you (“Approved: starting now.”). Approvals › “My requests” lists every request and its answer."
                    ]
                },
                {
                    heading: "Deciding as an approver",
                    steps: [
                        "Open Approvals (or the bell). “To review” lists what waits for you.",
                        "Check “Why it needs approval” and “Estimated cost”.",
                        "Press “Approve”, or “Deny…” with a reason the person sees. Nobody approves their own request."
                    ]
                },
                {
                    heading: "Team quotas",
                    paragraphs: ["A team can have a monthly limit on VM hours or cost. The start dialog shows the quota (“Team quota: … used; this start adds ~…”); over it, a start warns, needs approval, or is blocked."]
                }
            ],
            related: ['locks', 'approval-policies']
        },
        {
            id: 'activity-logs',
            group: 'access',
            title: "My activity and the audit log",
            roles: null,
            summary: "Everything is recorded: who did what, where and when, and how it ended.",
            keywords: ['activity', 'audit', 'log', 'history', 'who', 'export', 'csv', 'run by'],
            sections: [
                {
                    heading: "My activity",
                    steps: [
                        "Open My activity: what you started, stopped, locked and requested.",
                        "Pick 24 hours, 7 days, 30 days or “Dates…”, and filter by environment or action.",
                        "“Export CSV” downloads what you see."
                    ]
                },
                {
                    heading: "Audit log (environment admins and administrators)",
                    paragraphs: [
                        "Manage › Audit log shows everything anyone did in the environments you look after, with filters for period, person, environment, action and “Failed only”, and “Export CSV”.",
                        "Who shows the person, or what ran it: 'Schedule “Office hours”', 'The lease', 'Idle auto-stop' or 'The platform'."
                    ]
                }
            ],
            related: ['environments']
        },
        {
            id: 'chat-actions',
            group: 'access',
            title: "Microsoft Teams actions and Jira tickets",
            roles: null,
            summary: "Act on a notice straight from a Teams message, and turn a rightsizing suggestion into a Jira ticket.",
            optional: true,
            feature: 'chat',
            keywords: ['teams', 'chat', 'channel', 'button', 'jira', 'ticket', 'approve from teams', 'snooze'],
            sections: [
                {
                    heading: "Messages in Teams",
                    paragraphs: ["Environment admins can send an environment's notices to a Microsoft Teams channel. Some messages carry buttons, such as Approve, Extend lease or Snooze idle stop."]
                },
                {
                    heading: "Using a button",
                    steps: [
                        "Click the button in Teams; it opens this platform.",
                        "Sign in if asked and confirm. The action runs as you, with your access, and each link works once."
                    ]
                },
                {
                    heading: "Jira tickets",
                    paragraphs: ["Where Jira is connected, a rightsizing suggestion can be turned into a ticket with “Create Jira ticket”; the key then shows next to it (“View …”)."]
                }
            ]
        },

        // ------------------------------------------------------------------ Cost
        {
            id: 'costs',
            group: 'cost',
            title: "What things cost",
            roles: null,
            summary: "Every environment shows what it cost this month and where the month is heading.",
            keywords: ['cost', 'spend', 'estimate', 'actual', 'forecast', 'price', 'month to date', 'billing'],
            sections: [
                {
                    heading: "Where you see cost",
                    terms: [
                        ["Environment page › Cost this month", "Month to date, the change against the same days last month, the last 14 days and “If the current rate continues”."],
                        ["Environments list › This month", "Each environment's month to date."],
                        ["Start dialog › Estimated cost", "What this start will cost for the length chosen, or per hour."],
                        ["My account › Cost", "Month to date across the environments you can use."]
                    ]
                },
                {
                    heading: "How it is worked out",
                    paragraphs: [
                        "Estimates are each VM's running hours times the AWS list price for its type, region and operating system, plus storage. Where AWS billing figures have been fetched, they replace the estimate for that day.",
                        "Stopped VMs still cost a little: their disks and public IPs."
                    ]
                }
            ],
            related: ['locks', 'cost-management']
        },
        {
            id: 'assistant',
            group: 'cost',
            title: "Ask: questions about cost",
            roles: null,
            summary: "Ask about cost, idle VMs and savings in plain words. It only reads what you can already see.",
            optional: true,
            feature: 'assistant',
            keywords: ['ask', 'assistant', 'question', 'chat', 'ai'],
            sections: [
                {
                    heading: "Asking",
                    steps: [
                        "Open “Ask” from your avatar menu or the “?” menu.",
                        "Type a question about cost, idle VMs or savings, or pick a suggestion such as “Cost trend for 3 months”."
                    ],
                    paragraphs: ["It answers about the environments and cost you can see. It can't start, stop or change anything. It remembers the last 10 messages; “Start over” clears them."]
                }
            ]
        },

        // ------------------------------------------------------------------ Problems and questions
        {
            id: 'faq',
            group: 'trouble',
            title: "Problems and questions",
            roles: null,
            summary: "What a message means and what to do about it.",
            keywords: ['403', 'forbidden', 'stuck', 'missing', 'error', 'not found', 'help', 'reconnecting', 'not synced', "couldn't load", 'try again', 'ref', 'locked', 'no start button'],
            sections: [
                {
                    heading: "I can't start or stop",
                    terms: [
                        ["No Start or Stop buttons", "You have View only, or access to other groups only. Press “Ask for operator access”."],
                        ["Locked by Asha", "Someone holds the lock. Ask them, or wait until it ends."],
                        ["In progress", "Another start or stop is running here. Wait for it to end."],
                        ["Starts are blocked.", "The environment passed its budget, so new starts are frozen. Stops still work; an owner can lift it."],
                        ["Your start is waiting", "It needs approval; the approver is named."]
                    ]
                },
                {
                    heading: "Pages and messages",
                    terms: [
                        ["… didn't load · Try again", "That part failed to load; the rest of the page works. If it keeps failing, give the ref (for example “ref 7f3a92c1”) to your administrator."],
                        ["Reconnecting… VM lights may be a minute behind.", "The page lost touch with the server; it keeps trying."],
                        ["Not synced / an amber sync time", "States have not been read from AWS for a while. The platform keeps trying."],
                        ["… is for environment admins / for administrators", "Your role can't use that page. Nothing went wrong."],
                        ["You can't open this environment", "It no longer exists or you have no access. Press “Request access”."],
                        ["The platform is being updated. Try again in a few minutes.", "A new version is being installed."]
                    ]
                },
                {
                    heading: "Something looks wrong",
                    terms: [
                        ["A start looks stuck", "Large VMs can take several minutes. If nothing moves for a long time, press “Cancel the rest” and try again; the states correct themselves within minutes."],
                        ["A VM is missing", "It may be in another group, removed, or not added yet. Ask an environment admin."],
                        ["Started outside the platform", "Someone used AWS directly. Ask your team; the change is in History."],
                        ["A cost looks wrong", "Costs are estimates until AWS billing is fetched. Ask your administrator if one looks far off."]
                    ]
                },
                {
                    heading: "Still stuck?",
                    paragraphs: [ASK_ADMIN]
                }
            ],
            related: ['locks', 'sign-in', 'access']
        },

        // ------------------------------------------------------------------ Manage (environment admins)
        {
            id: 'access-admin',
            group: 'manage',
            title: "Giving and managing access",
            roles: ['ENV_ADMIN'],
            summary: "Decide access requests, give people access to your environments, and change or remove it.",
            keywords: ['access requests', 'approve', 'deny', 'decline', 'grant access', 'access management', 'revoke', 'remove', 'change level', 'extend access', 'team'],
            sections: [
                {
                    heading: "Which environments are yours",
                    paragraphs: [
                        "As an environment admin you manage the environments where you hold Admin access for the whole environment. Administrators manage all of them."
                    ]
                },
                {
                    heading: "Deciding a request",
                    steps: [
                        "Open Manage › Access requests (the number shows how many wait). Oldest first.",
                        "Read who asks, for which level, part and length, their reason, and what they have today.",
                        "Approve: pick a length under “Approve for” (theirs first, or 7, 30, 90 days, No end) and press “Approve”.",
                        "Or press “Deny…”, write a reason (they see it) and press “Decline request”."
                    ]
                },
                {
                    heading: "Giving access",
                    steps: [
                        "Open Access management › “Grant access” (or “Access” on an environment page).",
                        "1 · People: add people by name or email, or a whole team.",
                        "2 · Environments: tick environments; choose the whole environment or only some groups.",
                        "3 · Access: View only, Operator or Admin, for 30 days, 7, 90, no end, or until a date.",
                        "4 · Let them know: an optional note (kept in the audit log) and “Email each person what they can now use”.",
                        "Check “What will happen” (New, Extends, Upgrades, No change) and press “Grant access”."
                    ]
                },
                {
                    heading: "Changing or removing access",
                    paragraphs: [
                        "In Access management, find the person (filter “Ending within 7 days” shows what ends soon). “Change” sets the level and end date (“30 days from today”, “No end date”); “Revoke” removes it. History lists grants that ended. You can't change your own access; another admin has to.",
                        "Each change is recorded, and the person is told in the bell."
                    ]
                }
            ],
            related: ['access', 'activity-logs']
        },
        {
            id: 'vm-registry',
            group: 'manage',
            title: "VM registry",
            roles: ['ENV_ADMIN', 'ADMIN'],
            summary: "Set up environments: their groups, start order and the AWS VMs in them.",
            keywords: ['registry', 'register', 'add vm', 'group', 'start order', 'move', 'remove', 'discovery', 'review', 'needs review', 'found in aws', 'deactivate', 'drift', 'clear drift'],
            sections: [
                {
                    heading: "Adding VMs",
                    steps: [
                        "Open Manage › VM registry and pick the environment on the left.",
                        "Press “Add VMs from AWS”. Search by Name tag or IP; pick the region if asked.",
                        "Tick the instances, choose “Add to group” (or “New group…”) and press “Add 3 VMs”. They start after the group's current VMs."
                    ]
                },
                {
                    heading: "Groups and start order",
                    steps: [
                        "“Add group”: a name, its start order (1st, 2nd…) and “Waits for these groups to be running”. A choice that would make a loop is disabled.",
                        "On a VM, “Move” puts it at the end of another group; “Remove” takes it off the platform (its history is kept).",
                        "A group can be deleted once it is empty."
                    ]
                },
                {
                    heading: "Needs review",
                    terms: [
                        ["State changed outside the platform (Drift)", "Someone started or stopped it in AWS directly. The platform already shows what AWS reports. There is nothing to press: the mark clears by itself at the next state sync, normally within 5 minutes. If it keeps coming back, someone keeps changing the VM in AWS; its details show when."],
                        ["Found by discovery", "Found in AWS and placed here; check it and press “Mark as reviewed”."],
                        ["Not found in AWS; inactive", "The instance is gone from AWS."]
                    ],
                    paragraphs: ["The banner “N VMs need a look” with “Review them” opens this list. Administrators also see “Found in AWS”: instances in AWS that no environment holds, with “Add…” and “Ignore”."]
                }
            ],
            related: ['environment-settings', 'environments']
        },
        {
            id: 'environment-settings',
            group: 'manage',
            title: "Environment settings",
            roles: ['ENV_ADMIN'],
            summary: "Everything about one environment in one place. Open it with “Settings” on the environment page.",
            keywords: ['settings', 'details', 'start order', 'running and stopping', 'lease settings', 'idle rule', 'spot', 'notifications', 'teams channel', 'webhook', 'deactivate', 'production'],
            sections: [
                {
                    heading: "Sections",
                    terms: [
                        ["Details", "Name, description, team (who pays), AWS region and cost tags. Production is set by administrators; production environments are never stopped by idle auto-stop."],
                        ["Groups and start order", "Groups start top to bottom and stop bottom to top; ▲/▼ reorders them."],
                        ["Running and stopping", "Lease settings (when leases are on), and the idle auto-stop and schedule in use."],
                        ["Notifications", "“Email everyone with access…” tells everyone it is being stopped; Teams channels and webhooks where switched on."],
                        ["Deactivate", "Hides it from lists and schedules and releases its lock. VMs keep running in AWS: stop them first. Type its short name to confirm."]
                    ],
                    paragraphs: ["Press “Save changes” after editing; leaving with unsaved changes asks first."]
                },
                {
                    heading: "Lease settings",
                    paragraphs: ["“Stop when the lease ends” gives every start an end time. Set the “Default length” and the “Longest without approval”, up to the platform limit, and press “Save lease settings”."]
                },
                {
                    heading: "Idle auto-stop rule",
                    steps: [
                        "On the environment page press “Pause or review” on the Idle auto-stop panel.",
                        "Under “Rule · environment admins”: the whole environment or one group, “Idle for (min)”, “CPU below (%)” and “Network (MB/day)”.",
                        "New rules start as a “Dry run” that only records. “Stop idle VMs” becomes available after a few days of dry run. Press “Save rule”."
                    ]
                }
            ],
            related: ['vm-registry', 'automation-rules', 'budgets']
        },
        {
            id: 'automation-rules',
            group: 'manage',
            title: "Schedules and automation rules",
            roles: ['ENV_ADMIN'],
            summary: "Schedules start and stop environments at the same times each week. A rule never breaks a lock.",
            keywords: ['automation', 'schedule', 'calendar', 'holiday', 'override', 'rule', 'skip next stop', 'recent runs', 'skipped'],
            sections: [
                {
                    heading: "Making a schedule",
                    steps: [
                        "Open Manage › Automation rules and press “New schedule”.",
                        "Give it a name and pick the environment; tick “Only one group” to schedule one group.",
                        "Pick the days, then “Start at” and “Stop at”. Leave Start at empty to only stop it; people start it when they need it. “Advanced: different times per day” sets each day.",
                        "Check the time zone and “On holidays”, then the “This week” strip (“Runs N hours a week instead of 168”).",
                        "Press “Save schedule”."
                    ]
                },
                {
                    heading: "Exceptions",
                    paragraphs: [
                        "“Skip next stop” on a schedule skips one stop, with a reason (for example “release tonight”). Holiday calendars skip public holidays; administrators keep them."
                    ]
                },
                {
                    heading: "When a rule does not act",
                    terms: [
                        ["Environment locked", "A rule never breaks a lock. It retries, then skips, and the rule's owner is told."],
                        ["Operation in progress", "Another start or stop was running; the rule skipped."],
                        ["Turned off: …", "The platform switched the rule off; the card says why. Administrators are told."]
                    ],
                    paragraphs: ["“Recent runs” lists the last 30 days, with “Skipped” and “Failed” filters."]
                },
                {
                    heading: "When access is granted",
                    paragraphs: ["These rules start an environment or group when someone takes the lock or when an access request is approved. They only start; a schedule or the lease stops it."]
                }
            ],
            related: ['schedules', 'locking']
        },
        {
            id: 'approval-policies',
            group: 'manage',
            title: "Approval policies",
            roles: ['ENV_ADMIN'],
            optional: true,
            summary: "Decide when a start or an extension needs someone's yes before it runs.",
            keywords: ['approval policy', 'policy', 'approver', 'cost limit', 'hours', 'vcpu', 'instance type'],
            sections: [
                {
                    heading: "Making a policy",
                    steps: [
                        "Open Manage › Approval policies and press “New policy”.",
                        "Name it and choose what it applies to (your environments; administrators can pick all environments or a team).",
                        "Under “Needs approval when any of these is true”: a run longer than N hours, an estimated cost over $N, a VM type that matches (for example “m5.*”), or N vCPU or more.",
                        "Under “Decided by”, add people or a group (“Environment admins” by default) and whether any one or all must agree. With nobody picked, administrators decide.",
                        "Tick “On” and press “Create policy”."
                    ],
                    paragraphs: ["“Check a request” tests an environment and a length against your policies. While approvals are switched off for the platform, policies are kept but not applied."]
                }
            ],
            related: ['approvals']
        },
        {
            id: 'budgets',
            group: 'manage',
            title: "Budgets and alerts",
            roles: ['ENV_ADMIN'],
            summary: "A monthly limit for an environment or a team, with alerts as spend grows.",
            keywords: ['budget', 'alert', 'threshold', 'forecast', 'freeze', 'overspend', 'lift freeze'],
            sections: [
                {
                    heading: "Setting a budget",
                    steps: [
                        "Open Manage › Budgets and press “New budget” (or “Set budget” on an environment page).",
                        "Choose an environment or a team, and the monthly amount. It starts again on the 1st.",
                        "Add up to 4 alert percentages; owners are told each time spend reaches one.",
                        "Choose what happens when it is reached: “Alert only”, “Freeze new starts” or “Stop the environment”. Both actions need an owner's approval first, and only work where an administrator has switched them on.",
                        "Press “Create budget”."
                    ]
                },
                {
                    heading: "Reading the list",
                    terms: [
                        ["On track", "The forecast stays within the budget."],
                        ["Forecast over", "At this rate the month ends over budget."],
                        ["90% reached", "Spend has reached that share."],
                        ["Starts frozen until …", "The budget was reached; new starts are blocked. An owner can “Lift freeze”."]
                    ],
                    paragraphs: ["Spend uses AWS billing where available, otherwise the estimate; alerts count finished days only."]
                }
            ],
            related: ['costs', 'teams']
        },

        // ------------------------------------------------------------------ Administration
        {
            id: 'users',
            group: 'admin',
            title: "Users and adding people",
            roles: ['ADMIN'],
            summary: "Everyone who can sign in, their role, and how to add people and reset passwords.",
            keywords: ['users', 'add people', 'invite', 'role', 'deactivate', 'reset password', 'password', 'onboard', 'microsoft', 'directory'],
            sections: [
                {
                    heading: "Adding people",
                    steps: [
                        "Open Administration › Users and press “Add people”.",
                        "1 Who: type or paste email addresses (or find them in the Microsoft directory where it is switched on). Each becomes a chip; a chip marked “domain looks mistyped” offers “Use …”.",
                        "Choose “Sign in with”: “Microsoft account” for colleagues, “Email and a password” for people outside the directory.",
                        "2 Role: User, Environment admin or Administrator. An environment admin needs Admin access to at least one environment.",
                        "3 Access (optional): environment, level and length, then “Add”.",
                        "Check the summary bar and press “Add N people”."
                    ]
                },
                {
                    heading: "Passwords",
                    paragraphs: [
                        "With password sign-in, each person gets a generated password, shown once on the result screen. “Copy all” copies them; pass each one on yourself. Nothing is emailed. They choose their own at first sign-in.",
                        "To reset one for someone who signs in with a password only: “Reset password” on their row, then confirm. They are signed out everywhere; the new one-time password is shown once. People who sign in with Microsoft reset theirs with Microsoft."
                    ]
                },
                {
                    heading: "Changing someone",
                    paragraphs: [
                        "“Change role” sets User, Environment admin or Administrator; access to each environment is set in Access management. “Deactivate” stops them signing in and keeps their history; “Reactivate” brings them back. There must always be at least one administrator."
                    ]
                }
            ],
            related: ['access-admin', 'teams', 'sign-in']
        },
        {
            id: 'teams',
            group: 'admin',
            title: "Teams",
            roles: ['ADMIN'],
            summary: "Who owns which environments, which cost centre pays, and each team's monthly limit.",
            keywords: ['team', 'members', 'entra', 'group', 'cost centre', 'quota', 'limit', 'environments'],
            sections: [
                {
                    heading: "Setting up a team",
                    steps: [
                        "Open Administration › Teams and press “New team”: display name, short name and cost centre.",
                        "Open the team. Under Members, “Add people” by hand, or “Link a group” to keep its members in step with a Microsoft Entra group.",
                        "Use “Change which environments belong to …” to give it environments. Cost is then reported per team."
                    ]
                },
                {
                    heading: "Monthly limits",
                    paragraphs: ["Where team quotas are switched on, set a limit on VM hours or cost, when to warn, and what happens at the limit: “Warn only”, “Ask an owner to approve each start” or “Block new starts”. Limits start again on the 1st. Team budgets are under Manage › Budgets."]
                }
            ],
            related: ['users', 'chargeback', 'budgets']
        },
        {
            id: 'cost-management',
            group: 'admin',
            title: "Cost management",
            roles: ['ADMIN'],
            summary: "The fleet's spend, forecast and the savings to act on. Switch between Spend, Savings, Waste and Chargeback beside the page title.",
            keywords: ['cost management', 'spend', 'month to date', 'forecast', 'overview', 'by team', 'commitments', 'carbon', 'unusual spend', 'setup', 'export', 'billing', 'unregistered'],
            sections: [
                {
                    heading: "The figures",
                    terms: [
                        ["Month to date", "Each day uses the AWS billing figure where one exists, otherwise the estimate; the note says how much is from billing."],
                        ["Forecast", "Month to date plus the average of the last 7 days for each day left, or AWS's own forecast where it covers the month."],
                        ["Idle waste / Rightsizing", "What idle and oversized VMs cost a month."],
                        ["Not in the registry", "Instances in AWS that no environment holds; their cost is not counted here. “Review them” opens them."]
                    ]
                },
                {
                    heading: "Tabs",
                    terms: [
                        ["Overview", "The trend, spend by environment, team, account or VM type, and the biggest savings to act on."],
                        ["Rightsizing", "VMs that could be smaller or larger. See Rightsizing."],
                        ["Commitments", "Savings Plans and Reserved Instance coverage, and what AWS suggests buying (where switched on)."],
                        ["Carbon", "AWS's carbon figures per environment (where switched on)."],
                        ["Unusual spend", "Days that cost far more than usual; mark each Unexpected, Planned or Not unusual."],
                        ["Setup", "Which cost features work, and the AWS permissions missing."]
                    ]
                },
                {
                    heading: "Data and export",
                    paragraphs: ["“Data actions” fills in the last 30 days or fetches AWS billing now. “Export” gives the page as PDF or PNG, VM cost detail in Excel, or a month for finance as a FOCUS CSV."]
                }
            ],
            related: ['rightsizing', 'savings', 'chargeback', 'costs']
        },
        {
            id: 'rightsizing',
            group: 'admin',
            title: "Rightsizing",
            roles: ['ADMIN'],
            summary: "VMs that are bigger or smaller than they need to be, judged on their busiest minute.",
            keywords: ['rightsizing', 'resize', 'smaller', 'larger', 'graviton', 'compute optimizer', 'peak', 'cpu', 'busiest minute'],
            sections: [
                {
                    heading: "Reading a suggestion",
                    terms: [
                        ["CPU: busiest minute", "The peak is the busiest minute in the last 30 days, not an average. A VM that peaked high is not offered a smaller size."],
                        ["CPU: 5-minute averages", "Some days have no busiest-minute figure yet, so the real peak may be higher. Check before acting."],
                        ["AWS agrees / Suggested by AWS / AWS disagrees", "What AWS Compute Optimizer says about the same VM."],
                        ["A month", "What the change would save (or cost) a month."]
                    ]
                },
                {
                    heading: "Acting on it",
                    paragraphs: ["“Options” shows the other sizes. Where safe resize is switched on, “Resize safely…” stops the VM, changes its type and starts it again; a VM not healthy within 10 minutes goes back to its old size. “Create Jira ticket” hands it to the owning team where Jira is connected."]
                }
            ],
            related: ['cost-management', 'savings']
        },
        {
            id: 'savings',
            group: 'admin',
            title: "Savings and waste",
            roles: ['ADMIN'],
            summary: "What stopping VMs has saved, what is still on the table, and resources that cost money and do nothing.",
            keywords: ['savings', 'saved', 'efficiency', 'ledger', 'avoided hours', 'still on the table', 'waste', 'volumes', 'snapshots', 'public ip', 'gp3', 'fixes'],
            sections: [
                {
                    heading: "Savings",
                    terms: [
                        ["Saved, net", "Hours VMs were off, at On-Demand rates, minus the disks, public IPs and commitments that keep costing. Only stops the platform made count."],
                        ["Saved by", "Schedules, idle auto-stop, leases ending, stopped by hand, rightsizing, automation and budgets."],
                        ["Still on the table", "Idle VMs + rightsizing + waste, each VM counted once by its larger saving."],
                        ["Cost efficiency", "The share of spend that is not on the table. Higher is better."]
                    ]
                },
                {
                    heading: "Waste",
                    paragraphs: [
                        "Waste lists unattached volumes, unused public IPs, gp2 disks that could be gp3, VMs stopped over 30 days, old snapshots and idle VMs; “Everything” shows them all.",
                        "“Dismiss” one that is not waste, with a reason. Where fixes are switched on, propose one (for example “Snapshot and delete…”); another administrator approves it before anything changes in AWS, and “Fixes” follows it."
                    ]
                }
            ],
            related: ['cost-management', 'rightsizing']
        },
        {
            id: 'chargeback',
            group: 'admin',
            title: "Chargeback",
            roles: ['ADMIN'],
            optional: true,
            summary: "Each team's cost for a month, with shared costs split by rules, finalised and sent to finance.",
            keywords: ['chargeback', 'allocation', 'split', 'showback', 'finance', 'statement', 'finalise', 'cost centre', 'unassigned', 'missing tags'],
            sections: [
                {
                    heading: "The statement",
                    paragraphs: [
                        "Statement shows each team's own environments, its share of shared costs and the total, and how much is from AWS billing. It opens on last month. Environments with no team sit in Unassigned: “Assign teams” fixes that.",
                        "A month is a draft until it is finalised early the next month. “Finalise and send to finance” asks you to type the month; a finalised month no longer changes."
                    ]
                },
                {
                    heading: "Shared cost rules",
                    paragraphs: ["“New rule” splits an environment's cost “Evenly”, by “Fixed percentages” (totalling 100%) or “By own cost”, from a chosen month. One rule per environment."]
                }
            ],
            related: ['teams', 'cost-management']
        },
        {
            id: 'aws-accounts',
            group: 'admin',
            title: "AWS accounts",
            roles: ['ADMIN'],
            summary: "The AWS accounts the platform can reach, and the role it uses in each.",
            keywords: ['aws', 'account', 'role', 'arn', 'external id', 'regions', 'credentials', 'check access', 'organizations'],
            sections: [
                {
                    heading: "Adding an account",
                    steps: [
                        "Open Administration › AWS accounts and press “Add an account”.",
                        "Give a display name and the “Role ARN” the platform assumes (and an External ID if the role asks for one).",
                        "Optionally limit “Regions”; empty means every region the account has enabled.",
                        "Press “Add account”, then “Check access” to test the role."
                    ]
                },
                {
                    heading: "Reading the list",
                    paragraphs: ["Access shows “Platform credentials” or “Assumes … role”; Last check shows OK with the time, or the error. “Deactivate account” in Edit stops scanning it, and its environments can no longer start."]
                }
            ],
            related: ['system-health', 'settings']
        },
        {
            id: 'system-health',
            group: 'admin',
            title: "System health",
            roles: ['ADMIN'],
            summary: "Whether the platform's background work is running on time, and what changed outside it.",
            keywords: ['system health', 'jobs', 'scheduled jobs', 'late', 'sync', 'drift', 'outside the platform', 'reports', 'regions', 'fleet use'],
            sections: [
                {
                    heading: "The tiles",
                    terms: [
                        ["VM state sync", "When states were last read from AWS. Late means the sync has not run on time."],
                        ["Changed outside the platform, 24 h", "VMs started or stopped in AWS directly. Reports lists them."],
                        ["Actions, 24 h", "How many starts and stops succeeded; opens the Audit log."],
                        ["Scheduled jobs", "How many jobs ran on time."]
                    ]
                },
                {
                    heading: "When something is late or red",
                    steps: [
                        "Press “Problems first” in Scheduled jobs to see the late ones (“Never ran”, “… late”, “Switched off”).",
                        "Press “Check VM states now” to read every VM from AWS at once.",
                        "Check AWS accounts › “Check access” if an account's states can't be read.",
                        "A job that stays late after a restart needs the server logs: ask whoever runs the server."
                    ]
                },
                {
                    heading: "Other tabs",
                    paragraphs: ["Fleet use shows the fleet's state, metrics coverage and risks (each linking to where it is fixed); Regions shows the accounts and last scans; Reports lists state changes, locks, operations and emails sent."]
                }
            ],
            related: ['aws-accounts', 'settings']
        },
        {
            id: 'settings',
            group: 'admin',
            title: "Settings",
            roles: ['ADMIN'],
            summary: "Platform switches. Changes apply within 30 seconds; no restart needed.",
            keywords: ['settings', 'configuration', 'toggle', 'switch', 'threshold', 'restart', 'flag', '.env'],
            sections: [
                {
                    heading: "Changing a setting",
                    steps: [
                        "Open Administration › Settings and the section: Notifications, Access and security, Environments and leases, Automation, Cost, Integrations, AWS or Data retention.",
                        "Change the switches or values. A bar shows how many changes are not saved.",
                        "Press “Save changes”. Risky switches ask first. Each change is recorded in the audit log."
                    ],
                    paragraphs: ["“Reset” returns a row to its default. Rows marked “Set in .env” are read when the server starts: they are shown so you see the whole picture; change them in the server configuration and restart."]
                }
            ],
            related: ['system-health', 'api-access']
        },
        {
            id: 'api-access',
            group: 'admin',
            title: "API access",
            roles: ['ADMIN'],
            summary: "Tokens for scripts and tools, accounts for services, and webhooks.",
            keywords: ['api', 'token', 'bearer', 'service account', 'ci', 'pipeline', 'script', 'swagger', 'webhook', 'scopes'],
            sections: [
                {
                    heading: "Tokens",
                    steps: [
                        "Press “New token”, name it, tick its scopes and pick when it expires.",
                        "Copy it from “Token created for you”: it isn't shown again.",
                        "Send it as Authorization: Bearer vmc_… . It can never do more than its owner, and only what its scopes allow.",
                        "“Revoke” stops it at once if it may have leaked."
                    ]
                },
                {
                    heading: "Service accounts",
                    paragraphs: ["For pipelines and tools that should not act as a person. Each has an owner and a role, and gets environments in Access management like anyone else. Switching one off revokes its tokens; switching it on again does not bring them back."]
                },
                {
                    heading: "Switching tokens on",
                    paragraphs: ["Tokens are off until the server is configured: set the token secret API_TOKEN_PEPPER, then API_TOKENS_ENABLED=true, and restart. The API reference is at /swagger-ui.html when API_DOCS_ENABLED is on."]
                }
            ],
            related: ['settings']
        }
    ];

    /**
     * Help topic of each page, keyed by the router's page name (U02-T04), plus panels with their own
     * "?" (leases, idle-stop, my-account).
     */
    const PAGE_TOPICS = {
        'dashboard': 'home',
        'my-environments': 'environments',
        'environment-detail': 'environments',
        'request-access': 'access',
        'approvals': 'approvals',
        'activity-logs': 'activity-logs',
        'templates': 'templates',
        'help': 'getting-started',
        'chat-action': 'chat-actions',
        'pending-requests': 'access-admin',
        'access-management': 'access-admin',
        'access-grant': 'access-admin',
        'vm-registry': 'vm-registry',
        'register-vms': 'vm-registry',
        'environment-settings': 'environment-settings',
        'automation-rules': 'automation-rules',
        'automation-rule-new': 'automation-rules',
        'automation-rule-edit': 'automation-rules',
        'approval-policies': 'approval-policies',
        'budgets': 'budgets',
        'audit-logs-all': 'activity-logs',
        'cost-management': 'cost-management',
        'savings': 'savings',
        'waste': 'savings',
        'waste-fixes': 'savings',
        'chargeback': 'chargeback',
        'cost-setup': 'cost-management',
        'user-management': 'users',
        'user-management-add': 'users',
        'teams': 'teams',
        'team-detail': 'teams',
        'aws-accounts': 'aws-accounts',
        'api-access': 'api-access',
        'settings': 'settings',
        'system-health': 'system-health',
        // panels
        'leases': 'leases',
        'idle-stop': 'idle-stop',
        'my-account': 'my-account'
    };

    function all() {
        return TOPICS;
    }

    function find(id) {
        return TOPICS.find(t => t.id === id) || null;
    }

    /** The Help page's groups, in order (Help.dc.html). */
    const GROUPS = [['using', 'Using environments'], ['access', 'Access and notifications'], ['cost', 'Cost'],
        ['trouble', 'Problems and questions'], ['manage', 'Manage'], ['admin', 'Administration']];

    return { all, find, PAGE_TOPICS, GROUPS };
})();

window.HelpContent = HelpContent;
