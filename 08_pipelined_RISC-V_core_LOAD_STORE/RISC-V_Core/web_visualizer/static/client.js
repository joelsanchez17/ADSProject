const socket = io();

// 1. Send Commands to Python
function sendCommand(action) {
    socket.emit('command', { action: action });
}

// 2. Receive Updates from Python
socket.on('update', (data) => {
    renderDashboard(data);
    updateSVG(data);
});

function renderDashboard(data) {
    // Basic Text Updates
    document.getElementById('cycle-display').innerText = data.cycle || 0;

    // Format WB Info
    if (data.wb && data.wb.we) {
        document.getElementById('wb-info').innerText =
            `Reg: x${data.wb.rd}\nVal: ${data.wb.wdata}\nPC: ${data.pc.wb}`;
    } else {
        document.getElementById('wb-info').innerText = "Idle";
    }

    // Hazards
    let haz = [];
    if (data.hazard) {
        if (data.hazard.if_stall) haz.push("IF Stall");
        if (data.hazard.id_stall) haz.push("ID Stall");
        if (data.hazard.flush) haz.push("Flush");
    }
    document.getElementById('hazard-info').innerText = haz.join(", ") || "None";
}

function updateSVG(data) {
    const svgObj = document.getElementById('pipeline-svg');
    // Security check: ensure SVG is loaded
    if (!svgObj.contentDocument) return;
    const svg = svgObj.contentDocument;

    // 1. Update IF Stage Color
    const ifBox = svg.getElementById('stage-if');
    if (ifBox) {
        if (data.hazard && data.hazard.if_stall) {
            ifBox.style.fill = "#ffcccc"; // Light Red for Stall
            ifBox.style.stroke = "red";
        } else {
            ifBox.style.fill = "#ddd";    // Default Grey
            ifBox.style.stroke = "black";
        }
    }

    // 2. Update PC Text inside the box
    const pcText = svg.getElementById('text-pc-if');
    if (pcText && data.pc && data.pc.if) {
        // Convert integer PC to Hex string
        pcText.textContent = "PC: 0x" + data.pc.if.toString(16);
    }
}