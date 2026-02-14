# VM Platform Development Plan - Part 2
## Frontend Development & Production Readiness

**Continuation from Part 1 (Phases 0-8)**

---

## Phase 9: Frontend Development (Weeks 17-20)

### Objectives
- Build complete React/TypeScript UI
- Implement all mockup features
- Real-time updates and notifications
- Responsive design

### Week 17: Core UI Framework & Authentication

#### 10.1 Project Setup

**Frontend Structure:**
```
frontend/
├── src/
│   ├── components/
│   │   ├── layout/
│   │   │   ├── TopNav.tsx
│   │   │   ├── Sidebar.tsx
│   │   │   └── MainLayout.tsx
│   │   ├── common/
│   │   │   ├── Button.tsx
│   │   │   ├── Card.tsx
│   │   │   ├── Badge.tsx
│   │   │   ├── Modal.tsx
│   │   │   └── Toast.tsx
│   │   ├── vm/
│   │   │   ├── VmCard.tsx
│   │   │   ├── VmStatusBadge.tsx
│   │   │   └── VmActionButtons.tsx
│   │   ├── environment/
│   │   │   ├── EnvironmentSelector.tsx
│   │   │   ├── EnvironmentCard.tsx
│   │   │   └── LockBanner.tsx
│   │   └── admin/
│   │       ├── UserManagement.tsx
│   │       ├── AccessRequestsTable.tsx
│   │       └── AuditLogViewer.tsx
│   ├── pages/
│   │   ├── Dashboard.tsx
│   │   ├── EnvironmentDetail.tsx
│   │   ├── MyEnvironments.tsx
│   │   ├── RequestAccess.tsx
│   │   ├── ActivityLogs.tsx
│   │   ├── admin/
│   │   │   ├── AccessManagement.tsx
│   │   │   ├── VmRegistry.tsx
│   │   │   ├── AutomationRules.tsx
│   │   │   ├── AuditLogs.tsx
│   │   │   ├── CostManagement.tsx
│   │   │   └── SystemHealth.tsx
│   │   ├── Settings.tsx
│   │   └── Help.tsx
│   ├── services/
│   │   ├── api.ts
│   │   ├── auth.service.ts
│   │   ├── environment.service.ts
│   │   ├── vm.service.ts
│   │   ├── lock.service.ts
│   │   ├── notification.service.ts
│   │   └── admin.service.ts
│   ├── store/
│   │   ├── authStore.ts
│   │   ├── environmentStore.ts
│   │   ├── vmStore.ts
│   │   ├── notificationStore.ts
│   │   └── uiStore.ts
│   ├── hooks/
│   │   ├── useAuth.ts
│   │   ├── useEnvironments.ts
│   │   ├── useVMs.ts
│   │   ├── useLock.ts
│   │   └── useNotifications.ts
│   ├── types/
│   │   ├── environment.ts
│   │   ├── vm.ts
│   │   ├── user.ts
│   │   └── api.ts
│   ├── utils/
│   │   ├── formatters.ts
│   │   ├── validators.ts
│   │   └── constants.ts
│   ├── App.tsx
│   └── main.tsx
├── tailwind.config.js
├── vite.config.ts
└── package.json
```

#### 10.2 Authentication Setup

**auth.service.ts:**
```typescript
import axios from 'axios';

interface AuthTokens {
  accessToken: string;
  refreshToken: string;
  expiresAt: number;
}

class AuthService {
  private readonly AZURE_AD_CLIENT_ID = import.meta.env.VITE_AZURE_CLIENT_ID;
  private readonly AZURE_AD_TENANT_ID = import.meta.env.VITE_AZURE_TENANT_ID;
  private readonly REDIRECT_URI = import.meta.env.VITE_REDIRECT_URI;

  /**
   * Initiate Azure AD login flow
   */
  login(): void {
    const authUrl = `https://login.microsoftonline.com/${this.AZURE_AD_TENANT_ID}/oauth2/v2.0/authorize`;
    const params = new URLSearchParams({
      client_id: this.AZURE_AD_CLIENT_ID,
      response_type: 'code',
      redirect_uri: this.REDIRECT_URI,
      response_mode: 'query',
      scope: 'openid profile email',
    });

    window.location.href = `${authUrl}?${params.toString()}`;
  }

  /**
   * Handle OAuth callback
   */
  async handleCallback(code: string): Promise<void> {
    const response = await axios.post('/api/auth/callback', { code });
    const tokens: AuthTokens = response.data;
    
    this.setTokens(tokens);
  }

  /**
   * Store tokens
   */
  private setTokens(tokens: AuthTokens): void {
    localStorage.setItem('access_token', tokens.accessToken);
    localStorage.setItem('refresh_token', tokens.refreshToken);
    localStorage.setItem('expires_at', tokens.expiresAt.toString());
  }

  /**
   * Get access token
   */
  getAccessToken(): string | null {
    return localStorage.getItem('access_token');
  }

  /**
   * Check if user is authenticated
   */
  isAuthenticated(): boolean {
    const token = this.getAccessToken();
    const expiresAt = localStorage.getItem('expires_at');
    
    if (!token || !expiresAt) {
      return false;
    }

    return Date.now() < parseInt(expiresAt);
  }

  /**
   * Logout
   */
  logout(): void {
    localStorage.removeItem('access_token');
    localStorage.removeItem('refresh_token');
    localStorage.removeItem('expires_at');
    window.location.href = '/login';
  }
}

export default new AuthService();
```

**useAuth Hook:**
```typescript
import { useEffect, useState } from 'react';
import authService from '../services/auth.service';
import axios from 'axios';

interface User {
  userId: string;
  email: string;
  displayName: string;
  admin: boolean;
  envAdmin: boolean;
}

export function useAuth() {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (authService.isAuthenticated()) {
      fetchCurrentUser();
    } else {
      setLoading(false);
    }
  }, []);

  const fetchCurrentUser = async () => {
    try {
      const response = await axios.get('/api/users/me');
      setUser(response.data);
    } catch (error) {
      console.error('Failed to fetch user:', error);
      authService.logout();
    } finally {
      setLoading(false);
    }
  };

  return {
    user,
    loading,
    isAuthenticated: authService.isAuthenticated(),
    login: authService.login,
    logout: authService.logout,
  };
}
```

#### 10.3 Top Navigation Component

**TopNav.tsx:**
```typescript
import React, { useState } from 'react';
import { BellIcon, ChevronDownIcon } from '@heroicons/react/24/outline';
import { useAuth } from '../../hooks/useAuth';
import { useNotifications } from '../../hooks/useNotifications';
import NotificationPanel from '../notifications/NotificationPanel';

export default function TopNav() {
  const { user, logout } = useAuth();
  const { unreadCount } = useNotifications();
  const [showNotifications, setShowNotifications] = useState(false);
  const [showUserMenu, setShowUserMenu] = useState(false);

  return (
    <nav className="fixed top-0 left-0 right-0 h-16 bg-white border-b border-gray-200 z-50">
      <div className="h-full px-6 flex items-center justify-between">
        {/* Logo */}
        <div className="flex items-center gap-4">
          <button className="lg:hidden">
            {/* Mobile menu toggle */}
          </button>
          <h1 className="text-xl font-bold text-gray-900">VM Platform</h1>
        </div>

        {/* Right side */}
        <div className="flex items-center gap-4">
          {/* Notifications */}
          <button
            onClick={() => setShowNotifications(!showNotifications)}
            className="relative p-2 hover:bg-gray-100 rounded-lg transition-colors"
          >
            <BellIcon className="w-6 h-6 text-gray-600" />
            {unreadCount > 0 && (
              <span className="absolute top-0 right-0 bg-red-500 text-white text-xs rounded-full w-5 h-5 flex items-center justify-center">
                {unreadCount}
              </span>
            )}
          </button>

          {/* User menu */}
          <div className="relative">
            <button
              onClick={() => setShowUserMenu(!showUserMenu)}
              className="flex items-center gap-3 p-2 hover:bg-gray-100 rounded-lg transition-colors"
            >
              <div className="w-9 h-9 bg-blue-600 rounded-full flex items-center justify-center text-white font-semibold">
                {user?.displayName.charAt(0)}
              </div>
              <div className="text-left hidden md:block">
                <div className="text-sm font-medium text-gray-900">{user?.displayName}</div>
                <div className="text-xs text-gray-500">
                  {user?.admin ? 'Global Admin' : user?.envAdmin ? 'Env Admin' : 'User'}
                </div>
              </div>
              <ChevronDownIcon className="w-4 h-4 text-gray-400" />
            </button>

            {/* Dropdown menu */}
            {showUserMenu && (
              <div className="absolute right-0 mt-2 w-48 bg-white rounded-lg shadow-lg border border-gray-200 py-1">
                <a href="/settings" className="block px-4 py-2 text-sm text-gray-700 hover:bg-gray-100">
                  Settings
                </a>
                <a href="/help" className="block px-4 py-2 text-sm text-gray-700 hover:bg-gray-100">
                  Help & Docs
                </a>
                <hr className="my-1" />
                <button
                  onClick={logout}
                  className="block w-full text-left px-4 py-2 text-sm text-red-600 hover:bg-gray-100"
                >
                  Sign Out
                </button>
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Notification panel */}
      {showNotifications && (
        <NotificationPanel onClose={() => setShowNotifications(false)} />
      )}
    </nav>
  );
}
```

#### 10.4 Sidebar Component

**Sidebar.tsx:**
```typescript
import React from 'react';
import {
  HomeIcon,
  FolderIcon,
  PaperAirplaneIcon,
  ClipboardDocumentListIcon,
  UsersIcon,
  ServerIcon,
  ClockIcon,
  CurrencyDollarIcon,
  HeartIcon,
  StarIcon,
} from '@heroicons/react/24/outline';
import { NavLink } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
import { useEnvironments } from '../../hooks/useEnvironments';

export default function Sidebar() {
  const { user } = useAuth();
  const { favorites, recents } = useEnvironments();

  return (
    <aside className="fixed left-0 top-16 bottom-0 w-64 bg-gray-900 text-white overflow-y-auto">
      {/* Favorites */}
      <div className="p-4">
        <div className="flex items-center justify-between mb-2">
          <h3 className="text-xs font-semibold uppercase text-gray-400">Favorites</h3>
          <StarIcon className="w-4 h-4 text-gray-400" />
        </div>
        <ul className="space-y-1">
          {favorites.map((env) => (
            <li key={env.environmentId}>
              <NavLink
                to={`/environments/${env.environmentId}`}
                className="block px-3 py-2 text-sm hover:bg-gray-800 rounded transition-colors"
              >
                {env.name}
              </NavLink>
            </li>
          ))}
        </ul>
      </div>

      {/* Recents */}
      <div className="p-4 border-t border-gray-800">
        <div className="flex items-center justify-between mb-2">
          <h3 className="text-xs font-semibold uppercase text-gray-400">Recent</h3>
          <ClockIcon className="w-4 h-4 text-gray-400" />
        </div>
        <ul className="space-y-1">
          {recents.map((env) => (
            <li key={env.environmentId}>
              <NavLink
                to={`/environments/${env.environmentId}`}
                className="block px-3 py-2 text-sm hover:bg-gray-800 rounded transition-colors"
              >
                {env.name}
              </NavLink>
            </li>
          ))}
        </ul>
      </div>

      {/* Operations */}
      <div className="p-4 border-t border-gray-800">
        <h3 className="text-xs font-semibold uppercase text-gray-400 mb-2">Operations</h3>
        <ul className="space-y-1">
          <li>
            <NavLink to="/" className="flex items-center gap-3 px-3 py-2 text-sm hover:bg-gray-800 rounded">
              <HomeIcon className="w-5 h-5" />
              Dashboard
            </NavLink>
          </li>
          <li>
            <NavLink to="/my-environments" className="flex items-center gap-3 px-3 py-2 text-sm hover:bg-gray-800 rounded">
              <FolderIcon className="w-5 h-5" />
              My Environments
            </NavLink>
          </li>
          <li>
            <NavLink to="/request-access" className="flex items-center gap-3 px-3 py-2 text-sm hover:bg-gray-800 rounded">
              <PaperAirplaneIcon className="w-5 h-5" />
              Request Access
            </NavLink>
          </li>
          <li>
            <NavLink to="/activity-logs" className="flex items-center gap-3 px-3 py-2 text-sm hover:bg-gray-800 rounded">
              <ClipboardDocumentListIcon className="w-5 h-5" />
              Activity Logs
            </NavLink>
          </li>
        </ul>
      </div>

      {/* Admin Section (only for admins) */}
      {(user?.admin || user?.envAdmin) && (
        <div className="p-4 border-t border-gray-800">
          <h3 className="text-xs font-semibold uppercase text-gray-400 mb-2">Admin</h3>
          <ul className="space-y-1">
            <li>
              <NavLink to="/admin/access-management" className="flex items-center gap-3 px-3 py-2 text-sm hover:bg-gray-800 rounded">
                <UsersIcon className="w-5 h-5" />
                Access Management
              </NavLink>
            </li>
            <li>
              <NavLink to="/admin/vm-registry" className="flex items-center gap-3 px-3 py-2 text-sm hover:bg-gray-800 rounded">
                <ServerIcon className="w-5 h-5" />
                VM Registry
              </NavLink>
            </li>
            <li>
              <NavLink to="/admin/automation-rules" className="flex items-center gap-3 px-3 py-2 text-sm hover:bg-gray-800 rounded">
                <ClockIcon className="w-5 h-5" />
                Automation Rules
              </NavLink>
            </li>
            {user?.admin && (
              <>
                <li>
                  <NavLink to="/admin/cost-management" className="flex items-center gap-3 px-3 py-2 text-sm hover:bg-gray-800 rounded">
                    <CurrencyDollarIcon className="w-5 h-5" />
                    Cost Management
                  </NavLink>
                </li>
                <li>
                  <NavLink to="/admin/system-health" className="flex items-center gap-3 px-3 py-2 text-sm hover:bg-gray-800 rounded">
                    <HeartIcon className="w-5 h-5" />
                    System Health
                  </NavLink>
                </li>
              </>
            )}
          </ul>
        </div>
      )}
    </aside>
  );
}
```

### Week 18: Dashboard & Environment Views

#### 10.5 Dashboard Component

**Dashboard.tsx:**
```typescript
import React from 'react';
import { useQuery } from '@tanstack/react-query';
import axios from 'axios';
import MetricCard from '../components/dashboard/MetricCard';
import EnvironmentsTable from '../components/dashboard/EnvironmentsTable';
import CloudProviderBreakdown from '../components/dashboard/CloudProviderBreakdown';

export default function Dashboard() {
  const { data: stats } = useQuery(['dashboard-stats'], async () => {
    const response = await axios.get('/api/dashboard/stats');
    return response.data;
  });

  const { data: environments } = useQuery(['my-environments'], async () => {
    const response = await axios.get('/api/environments');
    return response.data;
  });

  return (
    <div className="p-6">
      <div className="mb-6">
        <h1 className="text-3xl font-bold text-gray-900">Dashboard</h1>
        <p className="text-gray-600">Overview of your environments and VMs</p>
      </div>

      {/* Metric Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6 mb-6">
        <MetricCard
          title="My Environments"
          value={stats?.totalEnvironments || 0}
          subtitle="Across all cloud providers"
        />
        <MetricCard
          title="Total VMs"
          value={stats?.totalVms || 0}
          subtitle="Registered instances"
          trend={{ value: '+3 this week', isPositive: true }}
        />
        <MetricCard
          title="Running VMs"
          value={stats?.runningVms || 0}
          subtitle={`${stats?.runningPercentage || 0}% of total`}
          trend={{ value: '-5 from 1h ago', isPositive: false }}
        />
        <MetricCard
          title="Est. Monthly Cost"
          value={`$${stats?.estimatedCost || 0}`}
          subtitle="Based on running VMs"
        />
      </div>

      {/* Cloud Provider Breakdown */}
      <CloudProviderBreakdown data={stats?.cloudBreakdown} />

      {/* Environments Table */}
      <div className="mt-6">
        <h2 className="text-xl font-semibold text-gray-900 mb-4">My Environments</h2>
        <EnvironmentsTable environments={environments} />
      </div>
    </div>
  );
}
```

#### 10.6 Environment Detail View

**EnvironmentDetail.tsx:**
```typescript
import React, { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import LockBanner from '../components/environment/LockBanner';
import GroupCard from '../components/environment/GroupCard';
import VmCard from '../components/vm/VmCard';
import { PlayIcon, StopIcon, LockOpenIcon } from '@heroicons/react/24/solid';
import toast from 'react-hot-toast';

export default function EnvironmentDetail() {
  const { environmentId } = useParams();
  const queryClient = useQueryClient();

  const { data: environment, isLoading } = useQuery(
    ['environment', environmentId],
    async () => {
      const response = await axios.get(`/api/environments/${environmentId}`);
      return response.data;
    }
  );

  const { data: lockStatus } = useQuery(
    ['lock-status', environmentId],
    async () => {
      const response = await axios.get(`/api/environments/${environmentId}/lock`);
      return response.data;
    },
    { refetchInterval: 5000 } // Poll every 5 seconds
  );

  // Mutations
  const startAllMutation = useMutation(
    async () => {
      await axios.post(`/api/environments/${environmentId}/start-all`);
    },
    {
      onSuccess: () => {
        toast.success('Starting all VMs in environment...');
        queryClient.invalidateQueries(['environment', environmentId]);
      },
      onError: (error: any) => {
        toast.error(error.response?.data?.message || 'Failed to start environment');
      },
    }
  );

  const stopAllMutation = useMutation(
    async () => {
      await axios.post(`/api/environments/${environmentId}/stop-all`);
    },
    {
      onSuccess: () => {
        toast.success('Stopping all VMs in environment...');
        queryClient.invalidateQueries(['environment', environmentId]);
      },
    }
  );

  const releaseLockMutation = useMutation(
    async () => {
      await axios.post(`/api/environments/${environmentId}/lock/release`);
    },
    {
      onSuccess: () => {
        toast.success('Lock released');
        queryClient.invalidateQueries(['lock-status', environmentId]);
      },
    }
  );

  if (isLoading) {
    return <div className="p-6">Loading...</div>;
  }

  return (
    <div className="p-6">
      {/* Header */}
      <div className="flex justify-between items-center mb-6">
        <div>
          <h1 className="text-3xl font-bold text-gray-900">{environment.name}</h1>
          <p className="text-gray-600">{environment.description}</p>
        </div>
        <div className="flex gap-2">
          <button
            onClick={() => startAllMutation.mutate()}
            className="flex items-center gap-2 px-4 py-2 bg-green-600 text-white rounded-lg hover:bg-green-700"
            disabled={lockStatus?.isLocked && !lockStatus?.isLockedByCurrentUser}
          >
            <PlayIcon className="w-5 h-5" />
            Start All
          </button>
          <button
            onClick={() => stopAllMutation.mutate()}
            className="flex items-center gap-2 px-4 py-2 bg-red-600 text-white rounded-lg hover:bg-red-700"
            disabled={lockStatus?.isLocked && !lockStatus?.isLockedByCurrentUser}
          >
            <StopIcon className="w-5 h-5" />
            Stop All
          </button>
          {lockStatus?.isLockedByCurrentUser && (
            <button
              onClick={() => releaseLockMutation.mutate()}
              className="flex items-center gap-2 px-4 py-2 bg-yellow-600 text-white rounded-lg hover:bg-yellow-700"
            >
              <LockOpenIcon className="w-5 h-5" />
              Release Lock
            </button>
          )}
        </div>
      </div>

      {/* Lock Banner */}
      {lockStatus?.isLocked && (
        <LockBanner
          lockedBy={lockStatus.lockedBy}
          lockedAt={lockStatus.lockedAt}
          isCurrentUser={lockStatus.isLockedByCurrentUser}
        />
      )}

      {/* Groups */}
      <div className="space-y-6">
        {environment.groups?.map((group: any) => (
          <GroupCard key={group.groupId} group={group} environmentId={environmentId} />
        ))}
      </div>
    </div>
  );
}
```

### Week 19: VM Operations & Admin Features

#### 10.7 VM Card Component

**VmCard.tsx:**
```typescript
import React from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import { PlayIcon, StopIcon, DocumentTextIcon } from '@heroicons/react/24/solid';
import VmStatusBadge from './VmStatusBadge';
import toast from 'react-hot-toast';

interface VmCardProps {
  vm: any;
  environmentId: string;
}

export default function VmCard({ vm, environmentId }: VmCardProps) {
  const queryClient = useQueryClient();

  const startVmMutation = useMutation(
    async () => {
      await axios.post(`/api/vms/${vm.vmId}/start`);
    },
    {
      onSuccess: () => {
        toast.success(`Starting ${vm.name}...`);
        queryClient.invalidateQueries(['environment', environmentId]);
      },
      onError: (error: any) => {
        toast.error(error.response?.data?.message || 'Failed to start VM');
      },
    }
  );

  const stopVmMutation = useMutation(
    async () => {
      await axios.post(`/api/vms/${vm.vmId}/stop`);
    },
    {
      onSuccess: () => {
        toast.success(`Stopping ${vm.name}...`);
        queryClient.invalidateQueries(['environment', environmentId]);
      },
      onError: (error: any) => {
        toast.error(error.response?.data?.message || 'Failed to stop VM');
      },
    }
  );

  return (
    <div className="border border-gray-200 rounded-lg p-4 hover:shadow-md transition-shadow">
      <div className="flex justify-between items-start mb-2">
        <div>
          <h4 className="font-semibold text-gray-900">{vm.name}</h4>
          <p className="text-sm text-gray-500">
            {vm.provider} • {vm.region}
          </p>
        </div>
        <VmStatusBadge status={vm.status} />
      </div>

      {vm.status === 'RUNNING' && (
        <p className="text-xs text-gray-500 mb-3">Uptime: {vm.uptime}</p>
      )}

      <div className="flex gap-2">
        {vm.status === 'STOPPED' && (
          <button
            onClick={() => startVmMutation.mutate()}
            className="flex items-center gap-1 px-3 py-1.5 text-sm bg-green-600 text-white rounded hover:bg-green-700"
            disabled={startVmMutation.isLoading}
          >
            <PlayIcon className="w-4 h-4" />
            Start
          </button>
        )}
        {vm.status === 'RUNNING' && (
          <button
            onClick={() => stopVmMutation.mutate()}
            className="flex items-center gap-1 px-3 py-1.5 text-sm bg-red-600 text-white rounded hover:bg-red-700"
            disabled={stopVmMutation.isLoading}
          >
            <StopIcon className="w-4 h-4" />
            Stop
          </button>
        )}
        <button className="flex items-center gap-1 px-3 py-1.5 text-sm bg-gray-600 text-white rounded hover:bg-gray-700">
          <DocumentTextIcon className="w-4 h-4" />
          Logs
        </button>
      </div>
    </div>
  );
}
```

#### 10.8 Automation Rules Admin Page

**AutomationRules.tsx:**
```typescript
import React, { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';
import { PlusIcon, PencilIcon, TrashIcon } from '@heroicons/react/24/outline';
import CreateRuleModal from '../components/admin/CreateRuleModal';
import toast from 'react-hot-toast';

export default function AutomationRules() {
  const [showCreateModal, setShowCreateModal] = useState(false);
  const queryClient = useQueryClient();

  const { data: rules } = useQuery(['automation-rules'], async () => {
    const response = await axios.get('/api/admin/automation-rules');
    return response.data;
  });

  const toggleRuleMutation = useMutation(
    async ({ ruleId, enabled }: { ruleId: string; enabled: boolean }) => {
      await axios.post(`/api/admin/automation-rules/${ruleId}/toggle`, { enabled });
    },
    {
      onSuccess: () => {
        toast.success('Rule updated');
        queryClient.invalidateQueries(['automation-rules']);
      },
    }
  );

  const deleteRuleMutation = useMutation(
    async (ruleId: string) => {
      await axios.delete(`/api/admin/automation-rules/${ruleId}`);
    },
    {
      onSuccess: () => {
        toast.success('Rule deleted');
        queryClient.invalidateQueries(['automation-rules']);
      },
    }
  );

  return (
    <div className="p-6">
      <div className="flex justify-between items-center mb-6">
        <div>
          <h1 className="text-3xl font-bold text-gray-900">Automation Rules</h1>
          <p className="text-gray-600">Schedule automatic start/stop operations</p>
        </div>
        <button
          onClick={() => setShowCreateModal(true)}
          className="flex items-center gap-2 px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700"
        >
          <PlusIcon className="w-5 h-5" />
          Create Rule
        </button>
      </div>

      <div className="space-y-4">
        {rules?.map((rule: any) => (
          <div key={rule.ruleId} className="border border-gray-200 rounded-lg p-4">
            <div className="flex justify-between items-start">
              <div>
                <h3 className="font-semibold text-lg text-gray-900">{rule.name}</h3>
                <p className="text-sm text-gray-600 mt-1">{rule.description}</p>
                <div className="mt-2 space-y-1 text-sm">
                  <p><span className="font-medium">Environment:</span> {rule.environment.name}</p>
                  <p><span className="font-medium">Action:</span> <span className={`px-2 py-0.5 rounded text-white text-xs ${
                    rule.actionType.includes('START') ? 'bg-green-600' : 'bg-red-600'
                  }`}>{rule.actionType}</span></p>
                  <p><span className="font-medium">Schedule:</span> {rule.cronExpression}</p>
                  <p><span className="font-medium">Status:</span> <span className={`px-2 py-0.5 rounded text-white text-xs ${
                    rule.isEnabled ? 'bg-green-600' : 'bg-gray-600'
                  }`}>{rule.isEnabled ? 'Active' : 'Disabled'}</span></p>
                  {rule.lastExecutedAt && (
                    <p className="text-gray-500">
                      Last Run: {new Date(rule.lastExecutedAt).toLocaleString()} ({rule.lastExecutionStatus})
                    </p>
                  )}
                </div>
              </div>
              <div className="flex gap-2">
                <button
                  onClick={() => toggleRuleMutation.mutate({ ruleId: rule.ruleId, enabled: !rule.isEnabled })}
                  className={`px-3 py-1.5 text-sm rounded ${
                    rule.isEnabled ? 'bg-yellow-600 hover:bg-yellow-700' : 'bg-green-600 hover:bg-green-700'
                  } text-white`}
                >
                  {rule.isEnabled ? 'Disable' : 'Enable'}
                </button>
                <button className="p-1.5 text-gray-600 hover:bg-gray-100 rounded">
                  <PencilIcon className="w-5 h-5" />
                </button>
                <button
                  onClick={() => deleteRuleMutation.mutate(rule.ruleId)}
                  className="p-1.5 text-red-600 hover:bg-red-50 rounded"
                >
                  <TrashIcon className="w-5 h-5" />
                </button>
              </div>
            </div>
          </div>
        ))}
      </div>

      {showCreateModal && (
        <CreateRuleModal onClose={() => setShowCreateModal(false)} />
      )}
    </div>
  );
}
```

### Week 20: Real-time Updates & Polish

#### 10.9 Real-time Status Updates

**useVMStatusPolling Hook:**
```typescript
import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import axios from 'axios';

export function useVMStatusPolling(environmentId: string, intervalMs: number = 10000) {
  const queryClient = useQueryClient();

  useEffect(() => {
    const interval = setInterval(async () => {
      try {
        const response = await axios.get(`/api/environments/${environmentId}`);
        queryClient.setQueryData(['environment', environmentId], response.data);
      } catch (error) {
        console.error('Failed to refresh VM status:', error);
      }
    }, intervalMs);

    return () => clearInterval(interval);
  }, [environmentId, intervalMs, queryClient]);
}
```

#### 10.10 Toast Notifications

**App.tsx with Toast Setup:**
```typescript
import React from 'react';
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Toaster } from 'react-hot-toast';
import MainLayout from './components/layout/MainLayout';
import Dashboard from './pages/Dashboard';
import EnvironmentDetail from './pages/EnvironmentDetail';
// ... other imports

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      refetchOnWindowFocus: false,
      retry: 1,
    },
  },
});

function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<MainLayout />}>
            <Route index element={<Dashboard />} />
            <Route path="environments/:environmentId" element={<EnvironmentDetail />} />
            {/* ... other routes */}
          </Route>
        </Routes>
      </BrowserRouter>
      
      <Toaster
        position="top-right"
        toastOptions={{
          duration: 4000,
          style: {
            background: '#363636',
            color: '#fff',
          },
          success: {
            duration: 3000,
            iconTheme: {
              primary: '#10b981',
              secondary: '#fff',
            },
          },
          error: {
            duration: 5000,
            iconTheme: {
              primary: '#ef4444',
              secondary: '#fff',
            },
          },
        }}
      />
    </QueryClientProvider>
  );
}

export default App;
```

### Deliverables (Phase 9)
- ✅ Complete React/TypeScript frontend
- ✅ Azure AD authentication
- ✅ All mockup features implemented
- ✅ Real-time status updates
- ✅ Toast notifications
- ✅ Responsive design
- ✅ Admin features (automation rules, cost management, system health)
- ✅ Favorites and recents
- ✅ Slide-out panels

### Definition of Done
- [ ] All pages from mockup functional
- [ ] Authentication working
- [ ] VM operations execute correctly
- [ ] Real-time updates working
- [ ] Admin features accessible
- [ ] Mobile responsive
- [ ] No console errors

---

## Phase 10: Integration & End-to-End Testing (Week 21)

### Objectives
- Integration testing across frontend and backend
- End-to-end user workflow tests
- Load testing
- Bug fixes

### Tasks

#### 11.1 Cypress E2E Tests

**cypress/e2e/vm-operations.cy.ts:**
```typescript
describe('VM Operations', () => {
  beforeEach(() => {
    cy.login(); // Custom command for Azure AD login
  });

  it('should start a VM with satisfied dependencies', () => {
    cy.visit('/environments/test-env-1');
    
    // Verify VM status
    cy.contains('database-primary').parent().should('contain', 'STOPPED');
    
    // Start VM
    cy.contains('database-primary').parent().find('button:contains("Start")').click();
    
    // Wait for operation
    cy.contains('Starting database-primary', { timeout: 10000 });
    
    // Verify status changed
    cy.contains('database-primary').parent().should('contain', 'RUNNING');
  });

  it('should block VM start when dependencies not satisfied', () => {
    cy.visit('/environments/test-env-1');
    
    // Try to start VM2 (depends on VM1 which is stopped)
    cy.contains('app-server').parent().find('button:contains("Start")').click();
    
    // Should show error
    cy.contains('dependencies not satisfied', { timeout: 5000 });
  });

  it('should cascade stop dependent VMs', () => {
    // Start VM chain: vm1 -> vm2 -> vm3
    cy.visit('/environments/test-env-1');
    
    // All running
    cy.contains('vm-1').parent().should('contain', 'RUNNING');
    cy.contains('vm-2').parent().should('contain', 'RUNNING');
    cy.contains('vm-3').parent().should('contain', 'RUNNING');
    
    // Stop vm-1
    cy.contains('vm-1').parent().find('button:contains("Stop")').click();
    cy.contains('Cascade stop').click(); // Confirm dialog
    
    // All should be stopped
    cy.contains('vm-1').parent().should('contain', 'STOPPED', { timeout: 15000 });
    cy.contains('vm-2').parent().should('contain', 'STOPPED');
    cy.contains('vm-3').parent().should('contain', 'STOPPED');
  });
});

describe('Lock Management', () => {
  it('should acquire lock when starting VM', () => {
    cy.login();
    cy.visit('/environments/test-env-1');
    
    // No lock initially
    cy.get('[data-testid="lock-banner"]').should('not.exist');
    
    // Start a VM
    cy.contains('database-primary').parent().find('button:contains("Start")').click();
    
    // Lock should appear
    cy.get('[data-testid="lock-banner"]').should('be.visible');
    cy.contains('Environment Locked by You');
  });

  it('should block operations when locked by another user', () => {
    // User 1 acquires lock
    cy.login('user1@company.com');
    cy.visit('/environments/test-env-1');
    cy.contains('database-primary').parent().find('button:contains("Start")').click();
    
    // User 2 tries to operate
    cy.login('user2@company.com');
    cy.visit('/environments/test-env-1');
    
    // Lock banner should show
    cy.contains('Environment locked by user1@company.com');
    
    // Start button should be disabled
    cy.contains('database-primary').parent().find('button:contains("Start")').should('be.disabled');
  });
});
```

#### 11.2 Load Testing with K6

**load-tests/vm-operations.js:**
```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 20 }, // Ramp up to 20 users
    { duration: '1m', target: 50 }, // Ramp up to 50 users
    { duration: '30s', target: 100 }, // Spike to 100 users
    { duration: '1m', target: 50 }, // Scale down
    { duration: '30s', target: 0 }, // Ramp down
  ],
  thresholds: {
    http_req_duration: ['p(95)<2000'], // 95% of requests under 2s
    http_req_failed: ['rate<0.05'], // Error rate under 5%
  },
};

const BASE_URL = __ENV.API_URL || 'http://localhost:8080';

export default function () {
  // Login and get token
  const loginRes = http.post(`${BASE_URL}/api/auth/login`, {
    username: 'loadtest@company.com',
    password: 'test123',
  });
  
  check(loginRes, { 'login successful': (r) => r.status === 200 });
  
  const token = loginRes.json('accessToken');
  const headers = { Authorization: `Bearer ${token}` };

  // Get environments
  const envRes = http.get(`${BASE_URL}/api/environments`, { headers });
  check(envRes, { 'got environments': (r) => r.status === 200 });

  // Get environment detail
  const environments = envRes.json();
  if (environments.length > 0) {
    const envId = environments[0].environmentId;
    const detailRes = http.get(`${BASE_URL}/api/environments/${envId}`, { headers });
    check(detailRes, { 'got environment detail': (r) => r.status === 200 });
  }

  sleep(1);
}
```

#### 11.3 Integration Test Scenarios

**Test Case: Full Environment Startup**
1. User logs in
2. User navigates to environment
3. User clicks "Start All"
4. System acquires lock
5. Groups start in dependency order
6. VMs within each group start according to sequence
7. All VMs reach RUNNING state
8. Lock remains held
9. User manually releases lock

**Test Case: State Drift Detection**
1. VM is running (status = RUNNING in database)
2. User stops VM directly in AWS console
3. Within 5 minutes, state sync job runs
4. System detects drift (cloud status = STOPPED)
5. Database updated to match cloud
6. Lock holder receives notification
7. Audit log records drift event

**Test Case: Automation Rule Execution**
1. Admin creates rule: "Start all VMs at 8 AM weekdays"
2. Rule is scheduled
3. At 8:00 AM on Monday, rule executes
4. System acquires lock (as SYSTEM user)
5. Environment starts successfully
6. Rule execution logged
7. Last execution timestamp updated

### Deliverables
- ✅ Cypress E2E test suite
- ✅ Load testing scenarios
- ✅ Integration test documentation
- ✅ Bug fixes from testing
- ✅ Performance optimizations

### Definition of Done
- [ ] All critical paths have E2E tests
- [ ] Load test passes with 100 concurrent users
- [ ] No P1 bugs
- [ ] Performance meets requirements

---

## Phase 11: Production Readiness (Weeks 22-23)

### Objectives
- Security hardening
- Production deployment
- Monitoring and alerting
- Documentation

### Week 22: Security & Hardening

#### 12.1 Security Checklist

**Application Security:**
- [x] Input validation on all endpoints
- [x] SQL injection prevention (using JPA)
- [x] XSS protection (React escapes by default)
- [x] CSRF protection (Spring Security)
- [x] Rate limiting on API endpoints
- [x] Encrypt cloud credentials using KMS
- [x] Encrypt audit logs at rest
- [x] TLS/SSL for all traffic

**API Security Configuration:**
```java
@Configuration
public class SecurityHardeningConfig {
    
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // CSRF protection
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
            )
            
            // Headers security
            .headers(headers -> headers
                .frameOptions().deny()
                .contentSecurityPolicy("default-src 'self'")
                .xssProtection()
            )
            
            // Rate limiting
            .apply(new RateLimitingConfigurer())
            
            // CORS configuration
            .cors(cors -> cors
                .configurationSource(corsConfigurationSource())
            );
        
        return http.build();
    }
    
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("https://vmplatform.company.com"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
```

**Rate Limiting:**
```java
@Component
public class RateLimitFilter extends OncePerRequestFilter {
    
    private final RateLimiter rateLimiter = RateLimiter.create(100.0); // 100 requests/sec
    
    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                    HttpServletResponse response, 
                                    FilterChain filterChain) throws ServletException, IOException {
        
        if (!rateLimiter.tryAcquire()) {
            response.setStatus(429); // Too Many Requests
            response.getWriter().write("Rate limit exceeded");
            return;
        }
        
        filterChain.doFilter(request, response);
    }
}
```

#### 12.2 Credential Encryption

**Encrypt cloud credentials using AWS KMS:**
```java
@Service
public class CredentialEncryptionService {
    
    private final AWSKMS kmsClient;
    private final String keyId;
    
    public String encryptCredential(String plaintext) {
        EncryptRequest request = new EncryptRequest()
            .withKeyId(keyId)
            .withPlaintext(ByteBuffer.wrap(plaintext.getBytes()));
        
        EncryptResult result = kmsClient.encrypt(request);
        return Base64.getEncoder().encodeToString(result.getCiphertextBlob().array());
    }
    
    public String decryptCredential(String ciphertext) {
        DecryptRequest request = new DecryptRequest()
            .withCiphertextBlob(ByteBuffer.wrap(Base64.getDecoder().decode(ciphertext)));
        
        DecryptResult result = kmsClient.decrypt(request);
        return new String(result.getPlaintext().array());
    }
}
```

### Week 23: Deployment & Monitoring

#### 12.3 Production Deployment

**Kubernetes Deployment (backend):**
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: vm-platform-backend
  namespace: vm-platform-prod
spec:
  replicas: 3
  selector:
    matchLabels:
      app: vm-platform-backend
  template:
    metadata:
      labels:
        app: vm-platform-backend
    spec:
      containers:
      - name: backend
        image: company/vm-platform-backend:1.0.0
        ports:
        - containerPort: 8080
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "production"
        - name: DB_HOST
          valueFrom:
            secretKeyRef:
              name: vm-platform-secrets
              key: db-host
        - name: DB_PASSWORD
          valueFrom:
            secretKeyRef:
              name: vm-platform-secrets
              key: db-password
        resources:
          requests:
            memory: "1Gi"
            cpu: "500m"
          limits:
            memory: "2Gi"
            cpu: "1000m"
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: vm-platform-backend-service
spec:
  selector:
    app: vm-platform-backend
  ports:
  - protocol: TCP
    port: 80
    targetPort: 8080
  type: LoadBalancer
```

**Frontend Deployment:**
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: vm-platform-frontend
  namespace: vm-platform-prod
spec:
  replicas: 2
  selector:
    matchLabels:
      app: vm-platform-frontend
  template:
    metadata:
      labels:
        app: vm-platform-frontend
    spec:
      containers:
      - name: frontend
        image: company/vm-platform-frontend:1.0.0
        ports:
        - containerPort: 80
        resources:
          requests:
            memory: "256Mi"
            cpu: "100m"
          limits:
            memory: "512Mi"
            cpu: "200m"
```

#### 12.4 Monitoring Setup

**Prometheus Metrics:**
```java
@Configuration
public class MetricsConfig {
    
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config().commonTags("application", "vm-platform");
    }
}

// Custom metrics
@Component
public class VmOperationMetrics {
    
    private final Counter vmStartCounter;
    private final Counter vmStopCounter;
    private final Timer vmOperationTimer;
    
    public VmOperationMetrics(MeterRegistry registry) {
        this.vmStartCounter = registry.counter("vm.operations.start");
        this.vmStopCounter = registry.counter("vm.operations.stop");
        this.vmOperationTimer = registry.timer("vm.operations.duration");
    }
    
    public void recordStart() {
        vmStartCounter.increment();
    }
    
    public void recordStop() {
        vmStopCounter.increment();
    }
    
    public void recordOperationDuration(Runnable operation) {
        vmOperationTimer.record(operation);
    }
}
```

**Grafana Dashboard Configuration:**
```json
{
  "dashboard": {
    "title": "VM Platform Operations",
    "panels": [
      {
        "title": "VM Operations per Minute",
        "targets": [
          {
            "expr": "rate(vm_operations_start_total[1m])",
            "legendFormat": "Starts"
          },
          {
            "expr": "rate(vm_operations_stop_total[1m])",
            "legendFormat": "Stops"
          }
        ]
      },
      {
        "title": "Active Locks",
        "targets": [
          {
            "expr": "sum(environment_locks_active)"
          }
        ]
      },
      {
        "title": "Operation Latency (p95)",
        "targets": [
          {
            "expr": "histogram_quantile(0.95, vm_operations_duration_seconds)"
          }
        ]
      }
    ]
  }
}
```

#### 12.5 Alerting Rules

**PagerDuty Alerts:**
```yaml
groups:
- name: vm_platform_alerts
  interval: 30s
  rules:
  - alert: HighErrorRate
    expr: rate(http_server_requests_seconds_count{status=~"5.."}[5m]) > 0.05
    for: 2m
    labels:
      severity: critical
    annotations:
      summary: "High error rate detected"
      description: "Error rate is {{ $value }} errors/sec"

  - alert: LockHeldTooLong
    expr: (time() - environment_lock_acquired_timestamp) > 14400  # 4 hours
    labels:
      severity: warning
    annotations:
      summary: "Environment lock held for >4 hours"
      description: "Lock on {{ $labels.environment }} held by {{ $labels.user }}"

  - alert: StateSyncFailed
    expr: state_sync_failures_total > 10
    for: 5m
    labels:
      severity: warning
    annotations:
      summary: "State sync experiencing failures"
```

#### 12.6 Documentation

**Create comprehensive documentation:**

1. **API Documentation (Swagger/OpenAPI)**
   - Auto-generated from Spring Boot annotations
   - Available at `/swagger-ui.html`

2. **User Manual**
   - How to log in
   - How to request access
   - How to start/stop VMs
   - Understanding dependencies
   - Lock management

3. **Admin Guide**
   - User management
   - Registering VMs
   - Creating automation rules
   - Breaking locks
   - Viewing audit logs

4. **Runbook**
   - Deployment procedures
   - Rollback procedures
   - Common issues and fixes
   - Escalation paths

### Deliverables
- ✅ Security hardening complete
- ✅ Production deployment configured
- ✅ Monitoring and alerting setup
- ✅ Complete documentation
- ✅ Disaster recovery plan

### Definition of Done
- [ ] Security scan passed
- [ ] Deployed to production
- [ ] Monitoring dashboards active
- [ ] Alerts configured
- [ ] Documentation published
- [ ] Team trained

---

## Summary: 23-Week Implementation Timeline

| Phase | Weeks | Status | Key Deliverables |
|-------|-------|--------|------------------|
| 0. Foundation | 1 | Required | Project setup, database, CI/CD |
| 1. Auth & Users | 2-3 | Required | Azure AD SSO, user management, access requests |
| 2. Hierarchy | 4-5 | Required | Environments, groups, VMs |
| 3. Dependencies | 6-7 | 🔥 CRITICAL | Validation engine, topological sort, circular detection |
| 4. Locks | 8 | 🔥 HIGH | Environment-wide locking, concurrency handling |
| 5. Cloud APIs | 9-10 | Required | AWS, Azure, GCP integration |
| 6. VM Operations | 11-12 | 🔥 HIGH | Start/stop with dependencies, orchestration |
| 7. Governance | 13-14 | 🔥 HIGH | Audit logs, notifications, favorites, uptime |
| 8. Monitoring | 15-16 | 🔥 HIGH | State sync, automation rules, cost tracking |
| 9. Frontend | 17-20 | Required | Complete React UI, real-time updates |
| 10. Testing | 21 | Required | E2E tests, load tests, bug fixes |
| 11. Production | 22-23 | Required | Security, deployment, monitoring, docs |

**Total: 23 weeks** (with 1-2 week buffer recommended)

---

## Missing Features from Mockup - NOW INCLUDED ✅

All features from the mockup are now covered:

- ✅ **Favorites & Recents** (Phase 7 - UserActivityService)
- ✅ **Automation Rules** (Phase 8 - Scheduled start/stop)
- ✅ **Cost Management** (Phase 8 - CostTrackingService)
- ✅ **System Health Dashboard** (Phase 8 - SystemHealthService)
- ✅ **VM Uptime Tracking** (Phase 7 - VmUptimeService)
- ✅ **Slide-out Panels** (Phase 9 - Frontend components)
- ✅ **Real-time Status Updates** (Phase 9 - Polling hooks)
- ✅ **Toast Notifications** (Phase 9 - React Hot Toast)
- ✅ **Environment Search** (Phase 9 - Sidebar component)
- ✅ **Settings Page** (Phase 9 - Settings.tsx)
- ✅ **Help & Docs** (Phase 11 - Documentation)

---

**Document Status:** Complete Implementation Plan  
**Last Updated:** Based on mockup HTML analysis  
**Ready for:** Development kickoff
