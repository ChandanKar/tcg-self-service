# Plan: Frontend Code Reorganization

**Date:** February 14, 2026  
**Status:** ✅ COMPLETE  
**Source:** `home.html` (2175 lines) → Organized structure  

---

## Summary

Successfully reorganized the monolithic `home.html` into a modular, maintainable structure with separate CSS, JavaScript, and template files.

---

## New Directory Structure

```
src/main/resources/static/
├── index.html                      # Main entry point (new)
│
├── css/
│   ├── main.css                    # Variables, global styles
│   ├── layout/
│   │   ├── topnav.css              # Top navigation
│   │   ├── sidebar.css             # Sidebar
│   │   └── content.css             # Main content area
│   └── components/
│       ├── cards.css               # Metric cards
│       ├── tables.css              # Tables
│       ├── badges.css              # Status badges
│       ├── buttons.css             # Buttons
│       ├── slideout.css            # Slide-out panels
│       └── modals.css              # Modals
│
├── js/
│   ├── app.js                      # Main initialization
│   ├── config.js                   # API endpoints, constants
│   ├── core/
│   │   ├── api-client.js           # AJAX wrapper
│   │   ├── template-loader.js      # Template loading & caching
│   │   ├── router.js               # Content routing
│   │   └── utils.js                # Utility functions
│   ├── ui/
│   │   ├── sidebar.js              # Sidebar interactions
│   │   ├── slideout.js             # Slide-out panels
│   │   └── notifications.js        # Toast notifications
│   └── features/
│       ├── dashboard.js            # Dashboard view
│       ├── environments.js         # Environment list/detail
│       └── features.js             # Other feature views
│
├── home.html                       # Original file (kept as backup)
├── home-original.html              # Original backup
├── logo/                           # Logo assets (unchanged)
└── old_backup/                     # NOT TOUCHED
```

---

## Files Created

### CSS Files (9 files)
| File | Description | Lines |
|------|-------------|-------|
| `css/main.css` | Variables, resets, global styles | 72 |
| `css/layout/topnav.css` | Top navigation bar | 135 |
| `css/layout/sidebar.css` | Sidebar and menu | 155 |
| `css/layout/content.css` | Main content area | 40 |
| `css/components/cards.css` | Metric and info cards | 85 |
| `css/components/tables.css` | Table styles | 35 |
| `css/components/badges.css` | Status badges | 40 |
| `css/components/buttons.css` | Button overrides | 35 |
| `css/components/slideout.css` | Slide-out panels | 60 |
| `css/components/modals.css` | Modal dialogs | 25 |

### JavaScript Files (11 files)
| File | Description | Lines |
|------|-------------|-------|
| `js/config.js` | API endpoints, constants | 80 |
| `js/core/api-client.js` | AJAX wrapper with error handling | 80 |
| `js/core/template-loader.js` | Template loading & rendering | 130 |
| `js/core/utils.js` | Utility functions | 120 |
| `js/core/router.js` | Content routing | 130 |
| `js/ui/sidebar.js` | Sidebar toggle, navigation | 130 |
| `js/ui/slideout.js` | Slide-out panel logic | 110 |
| `js/ui/notifications.js` | Toast notifications | 130 |
| `js/features/dashboard.js` | Dashboard view | 200 |
| `js/features/environments.js` | Environment views | 350 |
| `js/features/features.js` | Other feature views | 500 |
| `js/app.js` | Main initialization | 45 |

### HTML Files (1 file)
| File | Description |
|------|-------------|
| `index.html` | New entry point, loads all CSS/JS |

### Build Script
| File | Description |
|------|-------------|
| `build-frontend.ps1` | Concatenates CSS/JS for production |

---

## Architecture

### Module Pattern
All JavaScript uses the revealing module pattern for encapsulation:

```javascript
const ModuleName = (function() {
    'use strict';
    
    function publicMethod() { ... }
    function privateMethod() { ... }
    
    return {
        publicMethod
    };
})();
```

### Content Loading Flow
```
User clicks menu item
       ↓
Sidebar.handleNavigation()
       ↓
ContentRouter.loadContent(type)
       ↓
Feature.load() (e.g., Dashboard.load())
       ↓
Build HTML → Insert into #content-area
       ↓
Bind events
```

### Template Strategy
- **Small components:** JavaScript template strings
- **Future:** AJAX-loaded templates with TemplateLoader

---

## How to Use

### Development Mode
Open `index.html` in browser - loads individual CSS/JS files.

### Production Mode
1. Run build script:
   ```powershell
   .\build-frontend.ps1
   ```
2. Update `index.html` to use:
   ```html
   <link rel="stylesheet" href="dist/app.css">
   <script src="dist/app.js"></script>
   ```

---

## Not Changed
- `old_backup/` directory (as requested)
- `logo/` directory
- `home.html` kept as reference
- `home-original.html` kept as backup

---

## Next Steps

1. **Connect to Backend APIs** - Replace mock data in feature files
2. **Add error handling** - Loading states, error boundaries
3. **Add real-time updates** - Polling or WebSocket
4. **Add form validation** - Lock acquire, VM operations
5. **Add minification** - Install terser/clean-css for production builds

