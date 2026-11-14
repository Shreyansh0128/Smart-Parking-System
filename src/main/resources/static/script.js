const $ = (id) => document.getElementById(id);
const dialog = $("slotDialog");
let csrfToken = "";

async function api(url, options = {}) {
    const headers = { "Content-Type": "application/json", ...options.headers };
    if (options.method && !["GET", "HEAD", "OPTIONS"].includes(options.method.toUpperCase()) && csrfToken)
        headers["X-XSRF-TOKEN"] = csrfToken;
    const response = await fetch(url, { ...options, headers });
    if (!response.ok) {
        let message = `Request failed (${response.status})`;
        try { const body = await response.json(); message = body.detail || body.message || body.error || message; } catch { /* response may have no JSON body */ }
        throw new Error(message);
    }
    return response.status === 204 ? null : response.json();
}

function showNotice(message, kind = "success") {
    const notice = $("notice");
    notice.textContent = message;
    notice.className = `notice ${kind}`;
}

function automaticAllocationMessage(allocations = []) {
    const count = Array.isArray(allocations) ? allocations.length : allocations;
    if (!count) return "";
    return ` ${count} waiting request${count === 1 ? " was" : "s were"} automatically assigned.`;
}

async function loadDashboard() {
    $("slotGrid").setAttribute("aria-busy", "true");
    $("slotGrid").textContent = "Loading parking map…";
    const [stats, slots] = await Promise.all([api("/api/parking/stats"), api(slotFilterUrl())]);
    $("totalSlots").textContent = stats.totalSlots;
    $("availableSlots").textContent = stats.availableSlots;
    $("occupiedSlots").textContent = stats.occupiedSlots;
    $("reservedSlots").textContent = stats.reservedSlots;
    $("overdueSessions").textContent = stats.overdueSessions;
    $("outOfServiceSlots").textContent = stats.outOfServiceSlots;
    renderSlots(slots);
    $("slotGrid").setAttribute("aria-busy", "false");
}

function renderSlots(slots) {
    const grid = $("slotGrid");
    grid.replaceChildren();
    grid.setAttribute("aria-busy", "false");
    if (!slots.length) {
        const empty = document.createElement("p"); empty.className = "empty"; empty.textContent = "No slots match these filters.";
        grid.append(empty); return;
    }
    const floors = new Map();
    slots.forEach((slot) => {
        if (!floors.has(slot.floor)) {
            const floor = document.createElement("section");
            floor.className = "floor-section";
            floor.setAttribute("aria-label", `Floor ${slot.floor}`);
            const heading = document.createElement("h3");
            heading.textContent = `Floor ${slot.floor}`;
            const spaces = document.createElement("div");
            spaces.className = "slot-grid";
            floor.append(heading, spaces);
            grid.append(floor);
            floors.set(slot.floor, spaces);
        }
        const card = document.createElement("article"); card.className = "slot-card";
        const title = document.createElement("h4"); title.textContent = slot.slotNumber;
        const meta = document.createElement("p"); meta.textContent = slot.slotType;
        const status = document.createElement("span"); status.className = `status ${slot.status.toLowerCase()}`; status.textContent = slot.status.replaceAll("_", " ");
        card.append(title, meta, status);
        if (slot.registrationNumber) {
            const vehicle = document.createElement("p");
            vehicle.textContent = `Vehicle ${slot.registrationNumber}`;
            card.append(vehicle);
        }
        if (slot.expectedEndAt) {
            const expected = new Date(slot.expectedEndAt);
            const time = document.createElement("p");
            const overdue = expected.getTime() <= Date.now();
            time.className = overdue ? "overdue-time" : "expected-time";
            time.textContent = `${overdue ? "OVERDUE · expected " : "Expected end "}${expected.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })}`;
            card.append(time);
        }
        const controls = document.createElement("div"); controls.className = "slot-actions";
        const statusSelect = document.createElement("select"); statusSelect.setAttribute("aria-label", `Change status for ${slot.slotNumber}`);
        ["AVAILABLE", "OCCUPIED", "RESERVED", "OUT_OF_SERVICE"].forEach(value => { const option = document.createElement("option"); option.value = value; option.textContent = value.replaceAll("_", " "); option.selected = value === slot.status; statusSelect.append(option); });
        statusSelect.addEventListener("change", async () => {
            try {
                const result = await api(`/api/parking/slots/${slot.id}/status`, {
                    method: "PATCH", body: JSON.stringify({ status: statusSelect.value, version: slot.version })
                });
                showNotice(`${slot.slotNumber} status updated.${automaticAllocationMessage(result.automaticAllocations)}`);
                await Promise.all([loadDashboard(), loadWorkflow()]);
            }
            catch (error) { showNotice(error.message, "error"); await loadDashboard(); }
        });
        const edit = document.createElement("button"); edit.textContent = "Edit"; edit.addEventListener("click", () => openEdit(slot));
        const remove = document.createElement("button"); remove.textContent = "Delete"; remove.className = "danger";
        remove.addEventListener("click", async () => {
            if (!window.confirm(`Delete slot ${slot.slotNumber}?`)) return;
            try {
                await api(`/api/parking/slots/${slot.id}`, { method: "DELETE" });
                showNotice(`${slot.slotNumber} deleted.`);
                await Promise.all([loadDashboard(), loadWorkflow()]);
            }
            catch (error) { showNotice(error.message, "error"); }
        });
        controls.append(statusSelect, edit, remove); card.append(controls); floors.get(slot.floor).append(card);
    });
}

function populateSelect(select, rows, valueKey, labelFor) {
    select.replaceChildren();
    const placeholder = document.createElement("option");
    placeholder.value = "";
    placeholder.textContent = rows.length ? "Choose…" : "Register a record first";
    select.append(placeholder);
    rows.forEach((row) => {
        const option = document.createElement("option");
        option.value = row[valueKey];
        option.textContent = labelFor(row);
        select.append(option);
    });
}

function appendRecord(container, titleText, detailText, action) {
    const item = document.createElement("article");
    item.className = "record-card";
    const title = document.createElement("strong");
    title.textContent = titleText;
    const detail = document.createElement("p");
    detail.textContent = detailText;
    item.append(title, detail);
    if (action) item.append(action);
    container.append(item);
}

function renderWorkflowRecords(container, rows, title, details, actionFor) {
    container.replaceChildren();
    if (!rows.length) {
        const empty = document.createElement("p");
        empty.className = "empty";
        empty.textContent = "Nothing to show yet.";
        container.append(empty);
        return;
    }
    rows.forEach((row) => appendRecord(container, title(row), details(row), actionFor ? actionFor(row) : null));
}

async function loadWorkflow() {
    $("requestList").textContent = "Loading requests…";
    $("activeSessionList").textContent = "Loading active sessions…";
    $("historyList").textContent = "Loading history…";
    $("auditList").textContent = "Loading audit events…";
    $("reportList").textContent = "Loading reports…";
    const [users, vehicles, requests, sessions, history, simulation, audit, reports, notifications] = await Promise.all([
        api("/api/parking/users"), api("/api/parking/vehicles"), api("/api/parking/requests"),
        api("/api/parking/sessions/active"), api("/api/parking/history"),
        api("/api/parking/requests/scheduling/simulate"), api("/api/parking/audit?limit=30"),
        api("/api/parking/reports"), api("/api/parking/notifications?limit=50")
    ]);

    populateSelect($("vehicleOwner"), users, "id", (user) => `${user.fullName} (#${user.id})`);
    populateSelect($("requestVehicle"), vehicles, "id",
        (vehicle) => `${vehicle.registrationNumber} · ${vehicle.vehicleType}`);
    renderWorkflowRecords($("vehicleList"), vehicles,
        (vehicle) => vehicle.registrationNumber,
        (vehicle) => `${vehicle.vehicleType} · Owner ${vehicle.ownerName}`);
    renderWorkflowRecords($("requestList"), requests,
        (request) => `Request ${request.id} · ${request.status}`,
        (request) => `${request.registrationNumber} (${request.vehicleType}) · Priority ${request.priority} · ${new Date(request.requestedAt).toLocaleString()}`);
    renderWorkflowRecords($("activeSessionList"), sessions,
        (session) => `${session.registrationNumber} · ${session.slotNumber}`,
        (session) => {
            const overdue = session.expectedEndAt && new Date(session.expectedEndAt).getTime() <= Date.now();
            const ending = session.expectedEndAt
                ? `${overdue ? "OVERDUE · expected end " : "Expected end "}${new Date(session.expectedEndAt).toLocaleString()}`
                : "Duration unknown · reminder interval applies";
            return `Floor ${session.floor} · Started ${new Date(session.startedAt).toLocaleString()} · ${ending} · Fee estimate ${session.estimatedFee}`;
        },
        (session) => {
            const actions = document.createElement("div");
            actions.className = "slot-actions";
            const extend = document.createElement("button");
            extend.textContent = "Extend time";
            extend.addEventListener("click", () => extendSession(session.id));
            const checkout = document.createElement("button");
            checkout.className = "danger";
            checkout.textContent = "Confirm checkout";
            checkout.addEventListener("click", () => releaseSession(session.id));
            actions.append(extend, checkout);
            return actions;
        });
    renderWorkflowRecords($("historyList"), history,
        (entry) => `${entry.outcome} · ${entry.registrationNumber || `Request ${entry.requestId}`}`,
        (entry) => `${entry.slotNumber || "No slot"} · ${entry.allocatedAt ? new Date(entry.allocatedAt).toLocaleString() : ""}${entry.endedAt ? ` · Ended ${new Date(entry.endedAt).toLocaleString()}` : ""}`);
    renderWorkflowRecords($("auditList"), audit,
        (event) => `${event.eventType} · ${event.outcome}`,
        (event) => `${new Date(event.eventAt).toLocaleString()} · ${event.details || ""}`);
    const reportRows = [
        ...reports.occupancy.map((row) => ({
            title: `Occupancy · ${row.day}`,
            details: `${row.occupiedSlots}/${row.totalSlots} slots · ${row.utilizationPercent}%`
        })),
        ...reports.revenue.map((row) => ({
            title: `Revenue · ${row.day}`,
            details: `${row.closedSessions} closed sessions · ${row.revenue}`
        })),
        ...reports.allocationOutcomes.map((row) => ({
            title: `Allocation · ${row.day} · ${row.outcome}`,
            details: `${row.count} attempt(s)`
        }))
    ];
    renderWorkflowRecords($("reportList"), reportRows, (row) => row.title, (row) => row.details);
    $("simulationResult").textContent =
        `FCFS: ${simulation.fcfs.join(", ") || "none"} | Priority: ${simulation.priority.join(", ") || "none"} | Aging: ${simulation.priorityAging.join(", ") || "none"}`;
    $("unreadCount").textContent = notifications.unreadCount;
    renderNotifications(notifications.items);
}

async function releaseSession(sessionId) {
    if (!window.confirm("Confirm that this vehicle has checked out? This will close the session and free its slot.")) return;
    try {
        const result = await api(`/api/parking/sessions/${sessionId}/release`, { method: "POST" });
        showNotice(`Session released. Duration ${result.durationSeconds}s; fee ${result.fee}.${automaticAllocationMessage(result.automaticAllocations)}`);
        await Promise.all([loadDashboard(), loadWorkflow()]);
    } catch (error) { showNotice(error.message, "error"); }
}

async function extendSession(sessionId) {
    const input = window.prompt("Additional expected parking time in minutes:", "30");
    if (input === null) return;
    const additionalMinutes = Number(input);
    try {
        const session = await api(`/api/parking/sessions/${sessionId}/extend`, {
            method: "POST", body: JSON.stringify({ additionalMinutes })
        });
        showNotice(`Expected end updated to ${new Date(session.expectedEndAt).toLocaleString()}.`);
        await Promise.all([loadDashboard(), loadWorkflow()]);
    } catch (error) { showNotice(error.message, "error"); }
}

function renderNotifications(items) {
    const list = $("notificationList");
    list.replaceChildren();
    if (!items.length) {
        const empty = document.createElement("p");
        empty.className = "empty";
        empty.textContent = "No notifications yet.";
        list.append(empty);
        return;
    }
    items.forEach((notification) => {
        const card = document.createElement("article");
        card.className = `record-card${notification.acknowledgedAt ? " acknowledged" : " unread"}`;
        const message = document.createElement("p");
        message.textContent = notification.message;
        const when = document.createElement("time");
        when.dateTime = notification.createdAt;
        when.textContent = new Date(notification.createdAt).toLocaleString();
        card.append(message, when);
        if (notification.sessionId) {
            const actions = document.createElement("div");
            actions.className = "slot-actions";
            const extend = document.createElement("button");
            extend.textContent = "Extend session";
            extend.addEventListener("click", () => extendSession(notification.sessionId));
            const checkout = document.createElement("button");
            checkout.textContent = "Confirm checkout";
            checkout.className = "danger";
            checkout.addEventListener("click", () => releaseSession(notification.sessionId));
            actions.append(extend, checkout);
            card.append(actions);
        }
        if (!notification.acknowledgedAt) {
            const acknowledge = document.createElement("button");
            acknowledge.textContent = "Acknowledge";
            acknowledge.addEventListener("click", async () => {
                try {
                    await api(`/api/parking/notifications/${notification.id}/acknowledge`, { method: "POST" });
                    await loadWorkflow();
                } catch (error) { showNotice(error.message, "error"); }
            });
            card.append(acknowledge);
        } else {
            const state = document.createElement("span");
            state.className = "muted";
            state.textContent = "Acknowledged";
            card.append(state);
        }
        list.append(card);
    });
}

function openEdit(slot) {
    $("dialogTitle").textContent = "Edit parking slot";
    $("slotId").value = slot.id; $("slotNumber").value = slot.slotNumber;
    $("slotFloor").value = slot.floor; $("slotType").value = slot.slotType; $("slotStatus").value = slot.status;
    dialog.dataset.version = slot.version;
    dialog.showModal();
}

$("addSlotButton").addEventListener("click", () => {
    $("dialogTitle").textContent = "Add parking slot"; $("slotForm").reset(); $("slotId").value = ""; $("slotFloor").value = 0; $("slotStatus").value = "AVAILABLE"; dialog.showModal();
});
$("cancelDialog").addEventListener("click", () => dialog.close());
$("slotForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    const id = $("slotId").value;
    const body = {
        slotNumber: $("slotNumber").value, floor: Number($("slotFloor").value),
        slotType: $("slotType").value, status: $("slotStatus").value,
        version: id ? Number(dialog.dataset.version) : undefined
    };
    try {
        const result = await api(id ? `/api/parking/slots/${id}` : "/api/parking/slots", { method: id ? "PUT" : "POST", body: JSON.stringify(body) });
        dialog.close(); showNotice(`${id ? "Slot updated." : "Slot added."}${automaticAllocationMessage(result.automaticAllocations)}`);
        await Promise.all([loadDashboard(), loadWorkflow()]);
    } catch (error) { showNotice(error.message, "error"); }
});

function slotFilterUrl() {
    const params = new URLSearchParams();
    if ($("searchFilter").value.trim()) params.set("search", $("searchFilter").value.trim());
    if ($("statusFilter").value) params.set("status", $("statusFilter").value);
    if ($("floorFilter").value !== "") params.set("floor", $("floorFilter").value);
    if ($("vehicleTypeFilter").value) params.set("vehicleType", $("vehicleTypeFilter").value);
    return `/api/parking/slots?${params}`;
}

async function applyFilters() {
    try { renderSlots(await api(slotFilterUrl())); }
    catch (error) { showNotice(error.message, "error"); }
}
$("statusFilter").addEventListener("change", applyFilters);
$("floorFilter").addEventListener("input", applyFilters);
$("searchFilter").addEventListener("input", applyFilters);
$("vehicleTypeFilter").addEventListener("change", applyFilters);
$("clearFilters").addEventListener("click", () => {
    $("statusFilter").value = ""; $("floorFilter").value = "";
    $("searchFilter").value = ""; $("vehicleTypeFilter").value = ""; applyFilters();
});

$("userForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    try {
        await api("/api/parking/users", { method: "POST", body: JSON.stringify({
            fullName: $("userName").value, phone: $("userPhone").value || null
        }) });
        $("userForm").reset(); showNotice("User registered."); await loadWorkflow();
    } catch (error) { showNotice(error.message, "error"); }
});

$("vehicleForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    try {
        await api("/api/parking/vehicles", { method: "POST", body: JSON.stringify({
            userId: Number($("vehicleOwner").value), registrationNumber: $("vehicleRegistration").value,
            vehicleType: $("vehicleType").value
        }) });
        $("vehicleForm").reset(); showNotice("Vehicle registered."); await loadWorkflow();
    } catch (error) { showNotice(error.message, "error"); }
});

$("requestForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    try {
        const result = await api("/api/parking/requests", { method: "POST", body: JSON.stringify({
            vehicleId: Number($("requestVehicle").value), priority: Number($("requestPriority").value),
            expectedDurationMinutes: $("requestDuration").value ? Number($("requestDuration").value) : null
        }) });
        $("requestForm").reset(); $("requestPriority").value = 0;
        const allocations = result.automaticAllocations || [];
        const ownAllocation = allocations.find((allocation) => allocation.requestId === result.id);
        const otherCount = allocations.length - (ownAllocation ? 1 : 0);
        const message = ownAllocation
            ? `Request automatically assigned to ${ownAllocation.slot.slotNumber}.`
            : "Request submitted and waiting for a matching space.";
        showNotice(`${message}${automaticAllocationMessage(otherCount)}`);
        await Promise.all([loadDashboard(), loadWorkflow()]);
    } catch (error) { showNotice(error.message, "error"); }
});

$("runSimulation").addEventListener("click", async () => {
    try {
        const result = await api("/api/parking/requests/scheduling/simulate");
        $("simulationResult").textContent =
            `FCFS: ${result.fcfs.join(", ") || "none"} | Priority: ${result.priority.join(", ") || "none"} | Aging: ${result.priorityAging.join(", ") || "none"}`;
    } catch (error) { showNotice(error.message, "error"); }
});

$("notificationToggle").addEventListener("click", () => {
    const panel = $("notificationPanel");
    const isExpanded = $("notificationToggle").getAttribute("aria-expanded") === "true";
    $("notificationToggle").setAttribute("aria-expanded", String(!isExpanded));
    panel.hidden = isExpanded;
    if (!isExpanded) panel.scrollIntoView({ behavior: "smooth", block: "start" });
});

document.addEventListener("DOMContentLoaded", async () => {
    showNotice("Loading parking data…");
    try {
        const csrf = await api("/api/auth/csrf");
        csrfToken = csrf.token;
    } catch (error) {
        showNotice(`Owner sign-in is required: ${error.message}`, "error");
        $("slotGrid").textContent = "Sign in with the owner account to load parking data.";
        return;
    }
    const results = await Promise.allSettled([loadDashboard(), loadWorkflow()]);
    const failure = results.find((result) => result.status === "rejected");
    if (failure) {
        showNotice(`Could not load parking data: ${failure.reason.message}`, "error");
        $("slotGrid").textContent = "Parking map could not be loaded. Check the connection and retry.";
        $("slotGrid").setAttribute("aria-busy", "false");
        ["requestList", "activeSessionList", "historyList", "auditList", "reportList", "notificationList"]
            .forEach((id) => { $(id).textContent = "Could not load data."; });
    }
    else showNotice("Parking data loaded.");
});

$("logoutButton").addEventListener("click", async () => {
    try {
        await api("/api/auth/logout", { method: "POST" });
        window.location.assign("/");
    } catch (error) { showNotice(`Could not sign out: ${error.message}`, "error"); }
});

let refreshingDashboard = false;
window.setInterval(async () => {
    if (refreshingDashboard) return;
    refreshingDashboard = true;
    try {
        await Promise.all([loadDashboard(), loadWorkflow()]);
    } catch (error) {
        showNotice(`Could not refresh parking data: ${error.message}`, "error");
    } finally {
        refreshingDashboard = false;
    }
}, 60000);
