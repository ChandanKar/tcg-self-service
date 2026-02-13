/* ---------------- MOCK DATA ---------------- */

let data = [
  {
    name: "MCube GitLab",
    running: false,
    lock: null,
    vms: [
      { name: "mcube-gitlab-01", running: false, cores: 4, cpu: 30, mem: 50, uptime: 0 }
    ],
    subgroups: []
  },
  {
    name: "Pfizer-regenexbio-dev",
    running: false,
    lock: null,
    vms: [
      { name: "pfizer-regenex-01", running: false, cores: 4, cpu: 25, mem: 45, uptime: 0 },
      { name: "pfizer-regenex-02", running: false, cores: 4, cpu: 22, mem: 48, uptime: 0 },
      { name: "pfizer-regenex-03", running: false, cores: 2, cpu: 20, mem: 40, uptime: 0 },
      { name: "pfizer-regenex-04", running: false, cores: 2, cpu: 18, mem: 38, uptime: 0 },
      { name: "pfizer-regenex-05", running: false, cores: 2, cpu: 15, mem: 35, uptime: 0 }
    ],
    subgroups: []
  },
  {
    name: "MCube DEV-Servers",
    running: true,
    lock: "Dev-Team",
    vms: [
      { name: "mcube-dev-01", running: true, cores: 8, cpu: 45, mem: 65, uptime: 180 },
      { name: "mcube-dev-02", running: true, cores: 8, cpu: 42, mem: 62, uptime: 150 },
      { name: "mcube-dev-03", running: true, cores: 4, cpu: 38, mem: 58, uptime: 120 },
      { name: "mcube-dev-04", running: false, cores: 4, cpu: 30, mem: 50, uptime: 0 },
      { name: "mcube-dev-05", running: false, cores: 4, cpu: 28, mem: 48, uptime: 0 },
      { name: "mcube-dev-06", running: false, cores: 2, cpu: 25, mem: 45, uptime: 0 },
      { name: "mcube-dev-07", running: false, cores: 2, cpu: 22, mem: 42, uptime: 0 },
      { name: "mcube-dev-08", running: false, cores: 2, cpu: 20, mem: 40, uptime: 0 },
      { name: "mcube-dev-09", running: false, cores: 2, cpu: 18, mem: 38, uptime: 0 },
      { name: "mcube-dev-10", running: false, cores: 2, cpu: 15, mem: 35, uptime: 0 }
    ],
    subgroups: []
  },
  {
    name: "Analytics-Pharma-Mcube",
    running: false,
    lock: null,
    vms: [
      { name: "analytics-pharma-01", running: false, cores: 8, cpu: 35, mem: 60, uptime: 0 },
      { name: "analytics-pharma-02", running: false, cores: 8, cpu: 32, mem: 58, uptime: 0 },
      { name: "analytics-pharma-03", running: false, cores: 4, cpu: 28, mem: 55, uptime: 0 },
      { name: "analytics-pharma-04", running: false, cores: 4, cpu: 25, mem: 52, uptime: 0 },
      { name: "analytics-pharma-05", running: false, cores: 2, cpu: 20, mem: 48, uptime: 0 }
    ],
    subgroups: []
  },
  {
    name: "dev-build-environment",
    running: false,
    lock: null,
    vms: [
      { name: "dev-build-env-01", running: false, cores: 8, cpu: 40, mem: 55, uptime: 0 }
    ],
    subgroups: []
  },
  {
    name: "Mcube dev - on demand",
    running: false,
    lock: null,
    vms: [
      { name: "mcube-ondemand-01", running: false, cores: 4, cpu: 30, mem: 50, uptime: 0 },
      { name: "mcube-ondemand-02", running: false, cores: 4, cpu: 28, mem: 48, uptime: 0 },
      { name: "mcube-ondemand-03", running: false, cores: 2, cpu: 25, mem: 45, uptime: 0 }
    ],
    subgroups: []
  },
  {
    name: "BIOMAX",
    running: true,
    lock: "BioMax-Team",
    vms: [
      { name: "biomax-01", running: true, cores: 8, cpu: 48, mem: 68, uptime: 200 },
      { name: "biomax-02", running: true, cores: 8, cpu: 44, mem: 64, uptime: 185 },
      { name: "biomax-03", running: false, cores: 4, cpu: 35, mem: 58, uptime: 0 },
      { name: "biomax-04", running: false, cores: 4, cpu: 32, mem: 55, uptime: 0 },
      { name: "biomax-05", running: false, cores: 4, cpu: 30, mem: 52, uptime: 0 },
      { name: "biomax-06", running: false, cores: 2, cpu: 25, mem: 48, uptime: 0 },
      { name: "biomax-07", running: false, cores: 2, cpu: 22, mem: 45, uptime: 0 }
    ],
    subgroups: []
  },
  {
    name: "flow-cytometry",
    running: false,
    lock: null,
    vms: [
      { name: "flow-cyto-01", running: false, cores: 4, cpu: 30, mem: 55, uptime: 0 },
      { name: "flow-cyto-02", running: false, cores: 4, cpu: 28, mem: 52, uptime: 0 },
      { name: "flow-cyto-03", running: false, cores: 4, cpu: 25, mem: 50, uptime: 0 },
      { name: "flow-cyto-04", running: false, cores: 2, cpu: 22, mem: 48, uptime: 0 },
      { name: "flow-cyto-05", running: false, cores: 2, cpu: 20, mem: 45, uptime: 0 }
    ],
    subgroups: []
  },
  {
    name: "Mcube-4550",
    running: false,
    lock: null,
    vms: [
      { name: "mcube-4550-01", running: false, cores: 4, cpu: 30, mem: 50, uptime: 0 },
      { name: "mcube-4550-02", running: false, cores: 4, cpu: 28, mem: 48, uptime: 0 },
      { name: "mcube-4550-03", running: false, cores: 2, cpu: 25, mem: 45, uptime: 0 },
      { name: "mcube-4550-04", running: false, cores: 2, cpu: 22, mem: 42, uptime: 0 },
      { name: "mcube-4550-05", running: false, cores: 2, cpu: 20, mem: 40, uptime: 0 }
    ],
    subgroups: []
  },
  {
    name: "mcube-dev-eks",
    running: false,
    lock: null,
    vms: [
      { name: "mcube-eks-01", running: false, cores: 8, cpu: 35, mem: 60, uptime: 0 }
    ],
    subgroups: []
  },
  {
    name: "Mcube-Databases-Server",
    running: false,
    lock: null,
    vms: [
      { name: "mcube-db-01", running: false, cores: 8, cpu: 40, mem: 70, uptime: 0 },
      { name: "mcube-db-02", running: false, cores: 8, cpu: 38, mem: 68, uptime: 0 },
      { name: "mcube-db-03", running: false, cores: 4, cpu: 35, mem: 65, uptime: 0 }
    ],
    subgroups: []
  },
  {
    name: "tcgmcube Demo Environment",
    running: false,
    lock: null,
    vms: [
      { name: "tcgmcube-demo-01", running: false, cores: 4, cpu: 30, mem: 55, uptime: 0 },
      { name: "tcgmcube-demo-02", running: false, cores: 4, cpu: 28, mem: 52, uptime: 0 },
      { name: "tcgmcube-demo-03", running: false, cores: 2, cpu: 25, mem: 50, uptime: 0 },
      { name: "tcgmcube-demo-04", running: false, cores: 2, cpu: 22, mem: 48, uptime: 0 },
      { name: "tcgmcube-demo-05", running: false, cores: 2, cpu: 20, mem: 45, uptime: 0 }
    ],
    subgroups: [
      { 
        name: "SemantX application", 
        running: false,
        vms: [
          { name: "semantx-app-01", running: false, cores: 4, cpu: 30, mem: 55, uptime: 0 },
          { name: "semantx-app-02", running: false, cores: 4, cpu: 28, mem: 52, uptime: 0 },
          { name: "semantx-app-03", running: false, cores: 2, cpu: 25, mem: 48, uptime: 0 }
        ]
      }
    ]
  }
];

let selectedGroup = null;

// Cost per hour based on cores
function getVMCost(cores) {
  const costMap = { 2: 0.10, 4: 0.20, 8: 0.40, 16: 0.80 };
  return costMap[cores] || 0.05;
}

// Calculate group total cost per hour
function getGroupCost(group) {
  let cost = 0;
  group.vms.forEach(vm => {
    if (vm.running) cost += getVMCost(vm.cores);
  });
  group.subgroups.forEach(sg => {
    sg.vms.forEach(vm => {
      if (vm.running) cost += getVMCost(vm.cores);
    });
  });
  return cost;
}

// Calculate total system cost
function getTotalCost() {
  let total = 0;
  data.forEach(g => { total += getGroupCost(g); });
  return total;
}

// Calculate group session cost (accumulated cost based on uptime)
function getGroupSessionCost(group) {
  let cost = 0;
  group.vms.forEach(vm => {
    if (vm.uptime > 0) {
      cost += getVMCost(vm.cores) * (vm.uptime / 60);
    }
  });
  group.subgroups.forEach(sg => {
    sg.vms.forEach(vm => {
      if (vm.uptime > 0) {
        cost += getVMCost(vm.cores) * (vm.uptime / 60);
      }
    });
  });
  return cost;
}

// Re-evaluate group running state from its VMs and subgroups
function recalcGroupState(group) {
  const anyGroupVmRunning = group.vms.some(vm => vm.running || vm.starting);
  const anySubVmRunning = group.subgroups.some(sg => sg.vms.some(vm => vm.running || vm.starting));
  const anySubgroupRunning = group.subgroups.some(sg => sg.running || sg.starting);
  group.running = anyGroupVmRunning || anySubVmRunning || anySubgroupRunning;
  if (!group.running && !group.starting) {
    group.lock = null;
  }
}

/* ---------------- UI RENDER ---------------- */

function renderGroups() {
  $("#groupList").empty();
  
  // Calculate totals
  let totalVMs = 0;
  let runningVMs = 0;
  data.forEach(g => {
    totalVMs += g.vms.length;
    g.subgroups.forEach(sg => { totalVMs += sg.vms.length; });
    runningVMs += g.vms.filter(vm => vm.running).length;
    g.subgroups.forEach(sg => { runningVMs += sg.vms.filter(vm => vm.running).length; });
  });
  
  let totalCost = getTotalCost();
  $("#totalVMs").text(totalVMs);
  $("#runningVMs").text(runningVMs);
  $("#costPerHour").text("$" + totalCost.toFixed(2));
  $("#costPerMonth").text("$" + (totalCost * 24 * 30).toFixed(2));
  
  // Populate group list sidebar
  data.forEach((g, i) => {
    let status = g.running ? "🟢" : "🔴";
    let lock = g.lock ? " 🔒" : "";
    let activeClass = (selectedGroup && data.indexOf(selectedGroup) === i) ? " active" : "";
    let item = `<li class="list-group-item group-item${activeClass}" data-index="${i}">
                  ${status} ${g.name}${lock}
                </li>`;
    $("#groupList").append(item);
  });
  
  // Populate group cost table
  $("#groupCostTable").empty();
  data.forEach((g, i) => {
    let totalGroupVMs = g.vms.length;
    g.subgroups.forEach(sg => { totalGroupVMs += sg.vms.length; });
    
    let runningGroupVMs = g.vms.filter(vm => vm.running).length;
    g.subgroups.forEach(sg => { runningGroupVMs += sg.vms.filter(vm => vm.running).length; });
    
    let groupCost = getGroupCost(g);
    let sessionCost = getGroupSessionCost(g);
    let statusClass = g.running ? 'running' : 'stopped';
    let statusIcon = g.running ? 'check_circle' : 'cancel';
    let statusText = g.running ? 'Running' : 'Stopped';
    let costColor = groupCost === 0 ? 'text-muted' : (groupCost < 1 ? 'text-success' : (groupCost < 2 ? 'text-warning' : 'text-danger'));
    let sessionCostColor = sessionCost === 0 ? 'text-muted' : (sessionCost < 1 ? 'text-success' : (sessionCost < 5 ? 'text-warning' : 'text-danger'));
    
    let row = `
      <tr class="group-row" data-index="${i}">
        <td><strong>${g.name}</strong></td>
        <td class="${statusClass}">
          <span class="material-icons" style="font-size: 16px;">${statusIcon}</span>
          ${statusText}
        </td>
        <td>${totalGroupVMs}</td>
        <td>${runningGroupVMs}</td>
        <td class="${costColor} fw-bold">$${groupCost.toFixed(2)}</td>
        <td class="${sessionCostColor} fw-bold">$${sessionCost.toFixed(2)}</td>
        <td class="${costColor}">$${(groupCost * 24).toFixed(2)}</td>
        <td class="${costColor}">$${(groupCost * 24 * 30).toFixed(2)}</td>
      </tr>
    `;
    $("#groupCostTable").append(row);
  });
}

function renderMain(group) {
  // Hide dashboard, show detail view
  $("#landingDashboard").hide();
  $("#vmDetailView").show();
  
  $("#mainTitle").text("Group: " + group.name);

  let tbody = $("#vmAccordion");
  tbody.empty();

  // Group level row (collapsible header)
  let groupActionBtn;
  if (group.starting) {
    groupActionBtn = `<button class="btn btn-link text-warning p-0" disabled title="Starting..." style="text-decoration: none;"><span class="material-icons" style="font-size: 24px;">hourglass_empty</span></button>`;
  } else if (group.running) {
    groupActionBtn = `<button class="btn btn-link text-danger p-0 stop-group" data-type="group" title="Stop Group" style="text-decoration: none;"><span class="material-icons" style="font-size: 24px;">stop_circle</span></button>`;
  } else {
    groupActionBtn = `<button class="btn btn-link text-success p-0 start-group" data-type="group" title="Start Group" style="text-decoration: none;"><span class="material-icons" style="font-size: 24px;">play_circle</span></button>`;
  }

  let groupStatusClass = group.starting ? 'starting' : (group.running ? 'running' : 'stopped');
  let groupStatusIcon = group.starting ? 'pending' : (group.running ? 'check_circle' : 'cancel');
  let groupStatusText = group.starting ? 'STARTING' : (group.running ? 'RUNNING' : 'STOPPED');

  tbody.append(`
    <tr class="table-primary fw-bold collapsible-row">
      <td>
        <span class="material-icons collapse-icon" data-target=".group-content">expand_more</span>
        GROUP
      </td>
      <td>${group.name}</td>
      <td class="${groupStatusClass}">
        <span class="material-icons ${group.starting ? 'spinning' : ''}" style="font-size: 18px;">${groupStatusIcon}</span>
        ${groupStatusText}
      </td>
      <td colspan="3">-</td>
      <td>-</td>
      <td>${groupActionBtn}</td>
    </tr>
  `);

  // Group VMs (collapsible content)
  group.vms.forEach((vm, vmIdx) => {
    let vmActionBtn;
    if (vm.starting) {
      vmActionBtn = `<button class="btn btn-link text-warning p-0" disabled title="Starting..." style="text-decoration: none;"><span class="material-icons" style="font-size: 22px;">hourglass_empty</span></button>`;
    } else if (vm.running) {
      vmActionBtn = `<button class="btn btn-link text-danger p-0 stop-vm" data-type="group-vm" data-vm="${vmIdx}" title="Stop VM" style="text-decoration: none;"><span class="material-icons" style="font-size: 22px;">stop_circle</span></button>`;
    } else {
      vmActionBtn = `<button class="btn btn-link text-success p-0 start-vm" data-type="group-vm" data-vm="${vmIdx}" title="Start VM" style="text-decoration: none;"><span class="material-icons" style="font-size: 22px;">play_circle</span></button>`;
    }

    let vmStatusClass = vm.starting ? 'starting' : (vm.running ? 'running' : 'stopped');
    let vmStatusIcon = vm.starting ? 'pending' : (vm.running ? 'check_circle' : 'cancel');
    let vmStatusText = vm.starting ? 'STARTING' : (vm.running ? 'RUNNING' : 'STOPPED');

    tbody.append(`
      <tr class="collapsible-content group-content">
        <td>&nbsp;&nbsp;<span class="material-icons" style="font-size: 16px;">computer</span> VM</td>
        <td>${vm.name}</td>
        <td class="${vmStatusClass}">
          <span class="material-icons ${vm.starting ? 'spinning' : ''}" style="font-size: 18px;">${vmStatusIcon}</span>
          ${vmStatusText}
        </td>
        <td>${vm.running ? vm.uptime + " min" : "-"}</td>
        <td><span class="material-icons" style="font-size: 16px;">memory</span> ${vm.cores}</td>
        <td><span class="material-icons" style="font-size: 16px;">speed</span> ${vm.cpu}%</td>
        <td><span class="material-icons" style="font-size: 16px;">storage</span> ${vm.mem}%</td>
        <td>${vmActionBtn}</td>
      </tr>
    `);
  });

  // Subgroups and their VMs
  group.subgroups.forEach((subgroup, sgIdx) => {
    let subgroupActionBtn;
    if (!group.running) {
      subgroupActionBtn = `<button class="btn btn-link text-secondary p-0" disabled title="Start the group first" style="text-decoration: none;"><span class="material-icons" style="font-size: 22px;">play_circle</span></button>`;
    } else if (subgroup.starting) {
      subgroupActionBtn = `<button class="btn btn-link text-warning p-0" disabled title="Starting..." style="text-decoration: none;"><span class="material-icons" style="font-size: 22px;">hourglass_empty</span></button>`;
    } else if (subgroup.running) {
      subgroupActionBtn = `<button class="btn btn-link text-danger p-0 stop-subgroup" data-sg="${sgIdx}" title="Stop SubGroup" style="text-decoration: none;"><span class="material-icons" style="font-size: 22px;">stop_circle</span></button>`;
    } else {
      subgroupActionBtn = `<button class="btn btn-link text-success p-0 start-subgroup" data-sg="${sgIdx}" title="Start SubGroup" style="text-decoration: none;"><span class="material-icons" style="font-size: 22px;">play_circle</span></button>`;
    }

    let subgroupStatusClass = subgroup.starting ? 'starting' : (subgroup.running ? 'running' : 'stopped');
    let subgroupStatusIcon = subgroup.starting ? 'pending' : (subgroup.running ? 'check_circle' : 'cancel');
    let subgroupStatusText = subgroup.starting ? 'STARTING' : (subgroup.running ? 'RUNNING' : 'STOPPED');
    const subgroupTarget = `.subgroup${sgIdx}-content`;

    tbody.append(`
      <tr class="table-info fw-bold collapsible-row">
        <td>
          <span class="material-icons collapse-icon collapsed" data-target="${subgroupTarget}">expand_more</span>
          SUBGROUP
        </td>
        <td>${subgroup.name}</td>
        <td class="${subgroupStatusClass}">
          <span class="material-icons ${subgroup.starting ? 'spinning' : ''}" style="font-size: 18px;">${subgroupStatusIcon}</span>
          ${subgroupStatusText}
        </td>
        <td colspan="3">-</td>
        <td>-</td>
        <td>${subgroupActionBtn}</td>
      </tr>
    `);

    // Subgroup VMs (collapsible content)
    subgroup.vms.forEach((vm, vmIdx) => {
      let vmActionBtn;
      if (vm.starting) {
        vmActionBtn = `<button class="btn btn-link text-warning p-0" disabled title="Starting..." style="text-decoration: none;"><span class="material-icons" style="font-size: 22px;">hourglass_empty</span></button>`;
      } else if (vm.running) {
        vmActionBtn = `<button class="btn btn-link text-danger p-0 stop-vm" data-type="subgroup-vm" data-sg="${sgIdx}" data-vm="${vmIdx}" title="Stop VM" style="text-decoration: none;"><span class="material-icons" style="font-size: 22px;">stop_circle</span></button>`;
      } else {
        vmActionBtn = `<button class="btn btn-link text-success p-0 start-vm" data-type="subgroup-vm" data-sg="${sgIdx}" data-vm="${vmIdx}" title="Start VM" style="text-decoration: none;"><span class="material-icons" style="font-size: 22px;">play_circle</span></button>`;
      }

      let vmStatusClass = vm.starting ? 'starting' : (vm.running ? 'running' : 'stopped');
      let vmStatusIcon = vm.starting ? 'pending' : (vm.running ? 'check_circle' : 'cancel');
      let vmStatusText = vm.starting ? 'STARTING' : (vm.running ? 'RUNNING' : 'STOPPED');

      tbody.append(`
        <tr class="collapsible-content subgroup${sgIdx}-content d-none">
          <td>&nbsp;&nbsp;&nbsp;&nbsp;<span class="material-icons" style="font-size: 16px;">computer</span> VM</td>
          <td>${vm.name}</td>
          <td class="${vmStatusClass}">
            <span class="material-icons ${vm.starting ? 'spinning' : ''}" style="font-size: 18px;">${vmStatusIcon}</span>
            ${vmStatusText}
          </td>
          <td>${vm.running ? vm.uptime + " min" : "-"}</td>
          <td><span class="material-icons" style="font-size: 16px;">memory</span> ${vm.cores}</td>
          <td><span class="material-icons" style="font-size: 16px;">speed</span> ${vm.cpu}%</td>
          <td><span class="material-icons" style="font-size: 16px;">storage</span> ${vm.mem}%</td>
          <td>${vmActionBtn}</td>
        </tr>
      `);
    });
  });
  
  // Toggle collapse only when clicking the icon
  $('.collapse-icon').off('click').on('click', function(e) {
    e.stopPropagation();
    const target = $(this).data('target');
    const isVisible = $(target).first().is(':visible');

    // Collapse all sections
    $('.collapsible-content').addClass('d-none');
    $('.collapse-icon').addClass('collapsed');

    // Expand the requested section if it was not visible
    if (!isVisible) {
      $(target).removeClass('d-none');
      $(this).removeClass('collapsed');
    }
  });

  // Initialize - only group expanded
  $('.collapsible-content').addClass('d-none');
  $('.group-content').removeClass('d-none');
  $('.collapse-icon').addClass('collapsed');
  $('.collapse-icon[data-target=".group-content"]').removeClass('collapsed');
}

function renderContext(group) {
  $("#contextPanel").html(`
    <b>Group:</b> ${group.name}<br>
    <b>Status:</b> ${group.running ? "Running" : "Stopped"}<br>
    <b>Lock:</b> ${group.lock || "None"}<br><br>
    <b>Sub-Groups:</b>
    <ul>
      ${group.subgroups.map(s => `
        <li>${s.name} - ${s.running ? "🟢" : "🔴"}</li>
      `).join("")}
    </ul>
  `);
}

/* ---------------- EVENTS ---------------- */

// Back to dashboard button
$(document).on("click", "#backToDashboard", function () {
  $("#vmDetailView").hide();
  $("#landingDashboard").show();
  selectedGroup = null;
  $(".group-item").removeClass("active");
  renderGroups();
});

// Logo click - go to home page
$(document).on("click", "#logoHome", function () {
  $("#vmDetailView").hide();
  $("#landingDashboard").show();
  selectedGroup = null;
  $(".group-item").removeClass("active");
  renderGroups();
});

// Click on sidebar group OR table row
$(document).on("click", ".group-item, .group-row", function () {
  let index = $(this).data("index");
  if (index === undefined || index === null) {
    console.error("Index not found on clicked element");
    return;
  }
  selectedGroup = data[parseInt(index)];
  if (!selectedGroup) {
    console.error("Group not found at index", index);
    return;
  }
  
  // Remove active class from all menu items and add to clicked one
  $(".group-item").removeClass("active");
  $(".group-item[data-index='" + index + "']").addClass("active");
  
  $("#landingDashboard").hide();
  $("#vmDetailView").show();
  renderMain(selectedGroup);
  renderContext(selectedGroup);
});

// Start/Stop Group
$(document).on("click", ".start-group", function () {
  if (selectedGroup.lock) {
    alert("Group is locked by another user");
    return;
  }
  
  // Set group and all VMs to starting state
  selectedGroup.starting = true;
  selectedGroup.vms.forEach(vm => { vm.starting = true; });
  renderGroups();
  renderMain(selectedGroup);
  renderContext(selectedGroup);
  
  // Simulate startup delay (2 seconds)
  setTimeout(() => {
    selectedGroup.starting = false;
    selectedGroup.running = true;
    selectedGroup.lock = "Chandan";
    selectedGroup.vms.forEach(vm => { 
      vm.starting = false;
      vm.running = true; 
      vm.uptime = 0; 
    });
    renderGroups();
    renderMain(selectedGroup);
    renderContext(selectedGroup);
  }, 2000);
});

$(document).on("click", ".stop-group", function () {
  if (selectedGroup.subgroups.some(s => s.running)) {
    alert("Stop sub-groups first!");
    return;
  }
  selectedGroup.running = false;
  selectedGroup.lock = null;
  selectedGroup.vms.forEach(vm => { vm.running = false; vm.uptime = 0; });
  recalcGroupState(selectedGroup);
  renderGroups();
  renderMain(selectedGroup);
  renderContext(selectedGroup);
});

// Start/Stop Subgroup
$(document).on("click", ".start-subgroup", function () {
  if (!selectedGroup || !selectedGroup.running) {
    alert("Start the group first");
    return;
  }
  let sgIdx = $(this).data("sg");
  let subgroup = selectedGroup.subgroups[sgIdx];
  
  // Set subgroup and all VMs to starting state
  subgroup.starting = true;
  subgroup.vms.forEach(vm => { vm.starting = true; });
  renderMain(selectedGroup);
  renderContext(selectedGroup);
  
  // Simulate startup delay (2 seconds)
  setTimeout(() => {
    subgroup.starting = false;
    subgroup.running = true;
    subgroup.vms.forEach(vm => { 
      vm.starting = false;
      vm.running = true; 
      vm.uptime = 0; 
    });
    recalcGroupState(selectedGroup);
    renderMain(selectedGroup);
    renderContext(selectedGroup);
  }, 2000);
});

$(document).on("click", ".stop-subgroup", function () {
  let sgIdx = $(this).data("sg");
  let subgroup = selectedGroup.subgroups[sgIdx];
  subgroup.running = false;
  subgroup.vms.forEach(vm => { vm.running = false; vm.uptime = 0; });
  recalcGroupState(selectedGroup);
  renderMain(selectedGroup);
  renderContext(selectedGroup);
});

// Start/Stop Individual VM
$(document).on("click", ".start-vm", function () {
  let type = $(this).data("type");
  let vmIdx = $(this).data("vm");
  
  let vm;
  if (type === "group-vm") {
    vm = selectedGroup.vms[vmIdx];
  } else if (type === "subgroup-vm") {
    let sgIdx = $(this).data("sg");
    vm = selectedGroup.subgroups[sgIdx].vms[vmIdx];
  }
  
  // Set VM to starting state
  vm.starting = true;
  renderMain(selectedGroup);
  renderContext(selectedGroup);
  
  // Simulate startup delay (2 seconds)
  setTimeout(() => {
    vm.starting = false;
    vm.running = true;
    vm.uptime = 0;
    recalcGroupState(selectedGroup);
    renderMain(selectedGroup);
    renderContext(selectedGroup);
  }, 2000);
});

$(document).on("click", ".stop-vm", function () {
  let type = $(this).data("type");
  let vmIdx = $(this).data("vm");
  
  if (type === "group-vm") {
    selectedGroup.vms[vmIdx].running = false;
    selectedGroup.vms[vmIdx].uptime = 0;
  } else if (type === "subgroup-vm") {
    let sgIdx = $(this).data("sg");
    selectedGroup.subgroups[sgIdx].vms[vmIdx].running = false;
    selectedGroup.subgroups[sgIdx].vms[vmIdx].uptime = 0;
  }
  recalcGroupState(selectedGroup);
  
  renderMain(selectedGroup);
  renderContext(selectedGroup);
});

// Handle sidebar menu item clicks (OPERATION, GOVERNANCE, ADMIN sections)
$(document).on("click", ".sidebar-item", function () {
  let page = $(this).data("page");
  
  // Only process if it's a page selector, not a group selector
  if (!page) return;
  
  // Remove active class from all sidebar items and groups
  $(".sidebar-item").removeClass("active");
  $(".group-item").removeClass("active");
  
  // Add active class to clicked item
  $(this).addClass("active");
  
  // Hide all pages
  $("#landingDashboard").hide();
  $("#vmDetailView").hide();
  $("#dashboard").hide();
  $("#myvms").hide();
  $("#requests").hide();
  $("#audit").hide();
  $("#cost").hide();
  $("#access").hide();
  $("#registry").hide();
  $("#automation").hide();
  
  // Show the selected page
  switch(page) {
    case "dashboard":
      $("#landingDashboard").show();
      selectedGroup = null;
      renderGroups();
      break;
    case "myvms":
      $("#myvms").show();
      initMyVmsPage();
      break;
    case "requests":
      $("#requests").show();
      break;
    case "audit":
      $("#audit").show();
      break;
    case "cost":
      $("#cost").show();
      break;
    case "access":
      $("#access").show();
      break;
    case "registry":
      $("#registry").show();
      break;
    case "automation":
      $("#automation").show();
      break;
  }
});

/* ---------------- MOCK UPTIME ---------------- */

setInterval(() => {
  data.forEach(g => {
    g.vms.forEach(vm => {
      if (vm.running) vm.uptime++;
    });
    g.subgroups.forEach(s => {
      s.vms.forEach(vm => {
        if (vm.running) vm.uptime++;
      });
    });
  });
  if (selectedGroup) renderMain(selectedGroup);
}, 60000);

/* ---------------- MY VMs PAGE ---------------- */

let selectedMyVMGroup = null;

// Mock data for Cloud, Region, Owner
const mockCloudRegions = [
  { cloud: "AWS", region: "us-east-1" },
  { cloud: "Azure", region: "eastus" },
  { cloud: "GCP", region: "us-central1" },
  { cloud: "AWS", region: "us-west-2" },
  { cloud: "Azure", region: "westeurope" }
];

function initMyVmsPage() {
  populateGroupsTable();
  
  // Clear previous selection
  selectedMyVMGroup = null;
  $("#vmsTableContainer").hide();
  
  // Group row click handler
  $(document).off("click", ".group-table-row").on("click", ".group-table-row", function() {
    const groupIndex = $(this).data("index");
    selectedMyVMGroup = data[groupIndex];
    
    // Highlight selected row
    $(".group-table-row").removeClass("active-group");
    $(this).addClass("active-group");
    
    // Show and populate VMs table
    populateVMsTable(selectedMyVMGroup);
    $("#vmsTableContainer").show();
    $("#selectedGroupName").text(selectedMyVMGroup.name);
  });
  
  // Clear selection button
  $(document).off("click", "#clearGroupSelection").on("click", "#clearGroupSelection", function() {
    selectedMyVMGroup = null;
    $(".group-table-row").removeClass("active-group");
    $("#vmsTableContainer").hide();
  });
  
  // VM Start/Stop handlers
  $(document).off("click", ".myvms-start-vm").on("click", ".myvms-start-vm", function(e) {
    e.stopPropagation();
    const vmIndex = $(this).data("vm");
    startVMInMyVms(vmIndex);
  });
  
  $(document).off("click", ".myvms-stop-vm").on("click", ".myvms-stop-vm", function(e) {
    e.stopPropagation();
    const vmIndex = $(this).data("vm");
    stopVMInMyVms(vmIndex);
  });
  
  // Group Start/Stop handlers
  $(document).off("click", ".myvms-start-group").on("click", ".myvms-start-group", function(e) {
    e.stopPropagation();
    const groupIndex = $(this).data("group");
    startGroupInMyVms(groupIndex);
  });
  
  $(document).off("click", ".myvms-stop-group").on("click", ".myvms-stop-group", function(e) {
    e.stopPropagation();
    const groupIndex = $(this).data("group");
    stopGroupInMyVms(groupIndex);
  });
}

function populateGroupsTable() {
  const tbody = $("#groupsTableBody");
  tbody.empty();
  
  data.forEach((group, index) => {
    const mockData = mockCloudRegions[index % mockCloudRegions.length];
    const subgroupNames = group.subgroups.map(sg => sg.name).join(", ") || "-";
    const statusClass = group.running ? 'running' : 'stopped';
    const statusIcon = group.running ? 'check_circle' : 'cancel';
    const statusText = group.running ? 'RUNNING' : 'STOPPED';
    const lockedBy = group.lock || "-";
    const owner = "Chandan"; // Mock owner
    
    let actionBtn;
    if (group.running) {
      actionBtn = `<button class="btn btn-sm btn-danger myvms-stop-group" data-group="${index}" title="Stop Group"><span class="material-icons" style="font-size: 18px;">stop_circle</span></button>`;
    } else {
      actionBtn = `<button class="btn btn-sm btn-success myvms-start-group" data-group="${index}" title="Start Group"><span class="material-icons" style="font-size: 18px;">play_circle</span></button>`;
    }
    
    const row = `
      <tr class="group-table-row" data-index="${index}">
        <td><strong>${group.name}</strong></td>
        <td>${subgroupNames}</td>
        <td>${mockData.cloud}</td>
        <td>${mockData.region}</td>
        <td class="${statusClass}">
          <span class="material-icons" style="font-size: 16px;">${statusIcon}</span>
          ${statusText}
        </td>
        <td>${lockedBy}</td>
        <td>${owner}</td>
        <td>${actionBtn}</td>
      </tr>
    `;
    
    tbody.append(row);
  });
}

function populateVMsTable(group) {
  const tbody = $("#vmsTableBody");
  tbody.empty();
  
  if (!group || group.vms.length === 0) {
    tbody.append('<tr><td colspan="8" class="text-center">No VMs in this group</td></tr>');
    return;
  }
  
  group.vms.forEach((vm, index) => {
    const statusClass = vm.running ? 'running' : 'stopped';
    const statusIcon = vm.running ? 'check_circle' : 'cancel';
    const statusText = vm.running ? 'RUNNING' : 'STOPPED';
    const uptimeMin = vm.uptime || 0;
    const uptimeHrs = Math.floor(uptimeMin / 60);
    const uptimeDisplay = uptimeHrs > 0 ? `${uptimeHrs}h ${uptimeMin % 60}m` : `${uptimeMin}m`;
    
    let actionBtn;
    if (vm.running) {
      actionBtn = `<button class="btn btn-sm btn-danger myvms-stop-vm" data-vm="${index}" title="Stop VM"><span class="material-icons" style="font-size: 18px;">stop_circle</span></button>`;
    } else {
      actionBtn = `<button class="btn btn-sm btn-success myvms-start-vm" data-vm="${index}" title="Start VM"><span class="material-icons" style="font-size: 18px;">play_circle</span></button>`;
    }
    
    const row = `
      <tr>
        <td><span class="material-icons" style="font-size: 16px;">computer</span> VM</td>
        <td>${vm.name}</td>
        <td class="${statusClass}">
          <span class="material-icons" style="font-size: 16px;">${statusIcon}</span>
          ${statusText}
        </td>
        <td>${uptimeDisplay}</td>
        <td>${vm.cores}</td>
        <td>${vm.cpu}%</td>
        <td>${vm.mem}%</td>
        <td>${actionBtn}</td>
      </tr>
    `;
    
    tbody.append(row);
  });
}

function startVMInMyVms(vmIndex) {
  if (selectedMyVMGroup && selectedMyVMGroup.vms[vmIndex]) {
    selectedMyVMGroup.vms[vmIndex].running = true;
    selectedMyVMGroup.vms[vmIndex].uptime = 0;
    
    // Update group status
    const anyVMRunning = selectedMyVMGroup.vms.some(vm => vm.running);
    selectedMyVMGroup.running = anyVMRunning;
    
    populateVMsTable(selectedMyVMGroup);
    populateGroupsTable();
  }
}

function stopVMInMyVms(vmIndex) {
  if (selectedMyVMGroup && selectedMyVMGroup.vms[vmIndex]) {
    selectedMyVMGroup.vms[vmIndex].running = false;
    
    // Update group status
    const anyVMRunning = selectedMyVMGroup.vms.some(vm => vm.running);
    selectedMyVMGroup.running = anyVMRunning;
    
    populateVMsTable(selectedMyVMGroup);
    populateGroupsTable();
  }
}

function startGroupInMyVms(groupIndex) {
  const group = data[groupIndex];
  if (group) {
    group.vms.forEach(vm => {
      vm.running = true;
      vm.uptime = 0;
    });
    group.running = true;
    
    if (selectedMyVMGroup === group) {
      populateVMsTable(selectedMyVMGroup);
    }
    populateGroupsTable();
  }
}

function stopGroupInMyVms(groupIndex) {
  const group = data[groupIndex];
  if (group) {
    group.vms.forEach(vm => {
      vm.running = false;
    });
    group.running = false;
    
    if (selectedMyVMGroup === group) {
      populateVMsTable(selectedMyVMGroup);
    }
    populateGroupsTable();
  }
}

/* ---------------- INIT ---------------- */

$(document).ready(function() {
  $("#landingDashboard").show();
  $("#vmDetailView").hide();
  renderGroups();
});
