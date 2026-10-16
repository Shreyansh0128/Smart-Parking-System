const $ = (id) => document.getElementById(id);
const dialog = $("slotDialog");

async function api(url, options = {}) {
    const response = await fetch(url, { ...options, headers: { "Content-Type": "application/json", ...options.headers } });
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

async function loadDashboard() {
    const [stats, slots] = await Promise.all([api("/api/parking/stats"), api("/api/parking/slots")]);
    $("totalSlots").textContent = stats.totalSlots;
    $("availableSlots").textContent = stats.availableSlots;
    $("occupiedSlots").textContent = stats.occupiedSlots;
    $("reservedSlots").textContent = stats.reservedSlots;
    renderSlots(slots);
}

function renderSlots(slots) {
    const grid = $("slotGrid");
    grid.replaceChildren();
    if (!slots.length) {
        const empty = document.createElement("p"); empty.className = "empty"; empty.textContent = "No slots match these filters.";
        grid.append(empty); return;
    }
    slots.forEach((slot) => {
        const card = document.createElement("article"); card.className = "slot-card";
        const title = document.createElement("h3"); title.textContent = slot.slotNumber;
        const meta = document.createElement("p"); meta.textContent = `Floor ${slot.floor} · ${slot.slotType}`;
        const status = document.createElement("span"); status.className = `status ${slot.status.toLowerCase()}`; status.textContent = slot.status;
        const controls = document.createElement("div"); controls.className = "slot-actions";
        const statusSelect = document.createElement("select"); statusSelect.setAttribute("aria-label", `Change status for ${slot.slotNumber}`);
        ["AVAILABLE", "OCCUPIED", "RESERVED"].forEach(value => { const option = document.createElement("option"); option.value = value; option.textContent = value; option.selected = value === slot.status; statusSelect.append(option); });
        statusSelect.addEventListener("change", async () => {
            try { await api(`/api/parking/slots/${slot.id}/status`, { method: "PATCH", body: JSON.stringify({ status: statusSelect.value }) }); showNotice(`${slot.slotNumber} status updated.`); await loadDashboard(); }
            catch (error) { showNotice(error.message, "error"); await loadDashboard(); }
        });
        const edit = document.createElement("button"); edit.textContent = "Edit"; edit.addEventListener("click", () => openEdit(slot));
        const remove = document.createElement("button"); remove.textContent = "Delete"; remove.className = "danger";
        remove.addEventListener("click", async () => {
            if (!window.confirm(`Delete slot ${slot.slotNumber}?`)) return;
            try { await api(`/api/parking/slots/${slot.id}`, { method: "DELETE" }); showNotice(`${slot.slotNumber} deleted.`); await loadDashboard(); }
            catch (error) { showNotice(error.message, "error"); }
        });
        controls.append(statusSelect, edit, remove); card.append(title, meta, status, controls); grid.append(card);
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
    const [users, vehicles, requests, sessions, history, simulation] = await Promise.all([
        api("/api/parking/users"), api("/api/parking/vehicles"), api("/api/parking/requests"),
        api("/api/parking/sessions/active"), api("/api/parking/history"),
        api("/api/parking/requests/scheduling/simulate")
    ]);

    populateSelect($("vehicleOwner"), users, "id", (user) => `${user.fullName} (#${user.id})`);
    populateSelect($("requestVehicle"), vehicles, "id",
        (vehicle) => `${vehicle.registrationNumber} · ${vehicle.vehicleType}`);
    renderWorkflowRecords($("vehicleList"), vehicles,
        (vehicle) => vehicle.registrationNumber,
        (vehicle) => `${vehicle.vehicleType} · Owner ${vehicle.ownerName}`);
    renderWorkflowRecords($("requestList"), requests,
        (request) => `Request ${request.id} · ${request.status}`,
        (request) => `${request.registrationNumber} (${request.vehicleType}) · Priority ${request.priority} · ${new Date(request.requestedAt).toLocaleString()}`,
        (request) => {
            if (request.status !== "PENDING") return null;
            const button = document.createElement("button");
            button.textContent = "Allocate this request";
            button.addEventListener("click", () => allocateRequest(request.id));
            return button;
        });
    renderWorkflowRecords($("activeSessionList"), sessions,
        (session) => `${session.registrationNumber} · ${session.slotNumber}`,
        (session) => `Floor ${session.floor} · Started ${new Date(session.startedAt).toLocaleString()} · Estimated fee ${session.estimatedFee}`,
        (session) => {
            const button = document.createElement("button");
            button.className = "danger";
            button.textContent = "Release and calculate fee";
            button.addEventListener("click", () => releaseSession(session.id));
            return button;
        });
    renderWorkflowRecords($("historyList"), history,
        (entry) => `${entry.outcome} · ${entry.registrationNumber || `Request ${entry.requestId}`}`,
        (entry) => `${entry.slotNumber || "No slot"} · ${entry.allocatedAt ? new Date(entry.allocatedAt).toLocaleString() : ""}${entry.endedAt ? ` · Ended ${new Date(entry.endedAt).toLocaleString()}` : ""}`);
    $("simulationResult").textContent =
        `FCFS: ${simulation.fcfs.join(", ") || "none"} | Priority: ${simulation.priority.join(", ") || "none"}`;
}

async function allocateRequest(requestId) {
    try {
        const result = await api(`/api/parking/requests/${requestId}/allocate`, {
            method: "POST", body: JSON.stringify({ algorithm: $("algorithmSelect").value })
        });
        showNotice(`Request ${result.requestId} allocated to ${result.slot.slotNumber}.`);
        await Promise.all([loadDashboard(), loadWorkflow()]);
    } catch (error) { showNotice(error.message, "error"); }
}

async function releaseSession(sessionId) {
    try {
        const result = await api(`/api/parking/sessions/${sessionId}/release`, { method: "POST" });
        showNotice(`Session released. Duration ${result.durationSeconds}s; fee ${result.fee}.`);
        await Promise.all([loadDashboard(), loadWorkflow()]);
    } catch (error) { showNotice(error.message, "error"); }
}

function openEdit(slot) {
    $("dialogTitle").textContent = "Edit parking slot";
    $("slotId").value = slot.id; $("slotNumber").value = slot.slotNumber;
    $("slotFloor").value = slot.floor; $("slotType").value = slot.slotType; $("slotStatus").value = slot.status;
    dialog.showModal();
}

$("addSlotButton").addEventListener("click", () => {
    $("dialogTitle").textContent = "Add parking slot"; $("slotForm").reset(); $("slotId").value = ""; $("slotFloor").value = 0; $("slotStatus").value = "AVAILABLE"; dialog.showModal();
});
$("cancelDialog").addEventListener("click", () => dialog.close());
$("slotForm").addEventListener("submit", async (event) => {
    event.preventDefault();
    const id = $("slotId").value;
    const body = { slotNumber: $("slotNumber").value, floor: Number($("slotFloor").value), slotType: $("slotType").value, status: $("slotStatus").value };
    try {
        await api(id ? `/api/parking/slots/${id}` : "/api/parking/slots", { method: id ? "PUT" : "POST", body: JSON.stringify(body) });
        dialog.close(); showNotice(id ? "Slot updated." : "Slot added."); await loadDashboard();
    } catch (error) { showNotice(error.message, "error"); }
});

async function applyFilters() {
    const params = new URLSearchParams();
    if ($("statusFilter").value) params.set("status", $("statusFilter").value);
    if ($("floorFilter").value !== "") params.set("floor", $("floorFilter").value);
    try { renderSlots(await api(`/api/parking/slots?${params}`)); }
    catch (error) { showNotice(error.message, "error"); }
}
$("statusFilter").addEventListener("change", applyFilters);
$("floorFilter").addEventListener("input", applyFilters);
$("clearFilters").addEventListener("click", () => { $("statusFilter").value = ""; $("floorFilter").value = ""; applyFilters(); });

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
        await api("/api/parking/requests", { method: "POST", body: JSON.stringify({
            vehicleId: Number($("requestVehicle").value), priority: Number($("requestPriority").value)
        }) });
        $("requestForm").reset(); $("requestPriority").value = 0;
        showNotice("Parking request submitted."); await loadWorkflow();
    } catch (error) { showNotice(error.message, "error"); }
});

$("allocateNextButton").addEventListener("click", async () => {
    try {
        const result = await api("/api/parking/requests/allocate-next", {
            method: "POST", body: JSON.stringify({ algorithm: $("algorithmSelect").value })
        });
        showNotice(`Request ${result.requestId} allocated to ${result.slot.slotNumber}.`);
        await Promise.all([loadDashboard(), loadWorkflow()]);
    } catch (error) { showNotice(error.message, "error"); }
});

$("runSimulation").addEventListener("click", async () => {
    try {
        const result = await api("/api/parking/requests/scheduling/simulate");
        $("simulationResult").textContent =
            `FCFS: ${result.fcfs.join(", ") || "none"} | Priority: ${result.priority.join(", ") || "none"}`;
    } catch (error) { showNotice(error.message, "error"); }
});

document.addEventListener("DOMContentLoaded", async () => {
    showNotice("Loading parking data…");
    const results = await Promise.allSettled([loadDashboard(), loadWorkflow()]);
    const failure = results.find((result) => result.status === "rejected");
    if (failure) showNotice(`Could not load parking data: ${failure.reason.message}`, "error");
    else showNotice("Parking data loaded.");
});
