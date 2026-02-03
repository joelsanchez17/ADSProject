const socket = io();
const regGrid = document.getElementById('reg-grid');

// --- 1. INITIALIZE REGISTER GRID ---
regGrid.innerHTML = "";
for (let i = 0; i < 32; i++) {
    const div = document.createElement('div');
    div.className = 'reg-box';
    div.id = `reg-box-${i}`;
    div.innerHTML = `<span class="reg-name">x${i}</span><div class="reg-val" id="reg-val-${i}">0</div>`;
    regGrid.appendChild(div);
}

// --- 2. KEYBOARD CONTROLS ---
document.addEventListener('keydown', (e) => {
    if (e.key === "ArrowRight") sendCommand('step');
    if (e.key === "ArrowLeft")  sendCommand('back');
});

function sendCommand(action) { socket.emit('command', { action: action }); }

// --- 3. MAIN UPDATE LOOP ---
socket.on('update', (packet) => {
    const data = packet.enriched;
    const regs = packet.registers;
    document.getElementById('cycle-display').innerText = data.cycle || 0;

    console.log("DEBUG DATA:", {
            rs1: data.id?.rs1,
            rs2: data.id?.rs2,
            opA: data.ex?.val_a,
            opB: data.ex?.val_b
    });
    // A. Update Register Grid Values
    for(let i=0; i<32; i++) {
        const box = document.getElementById(`reg-box-${i}`);
        box.classList.remove('active-write', 'active-read');
        document.getElementById(`reg-val-${i}`).innerText = "0x" + regs[i].toString(16);
    }

    // B. Highlight Writeback (Green)
    if (data.wb && data.wb.we && data.wb.rd !== 0) {
        document.getElementById(`reg-box-${data.wb.rd}`)?.classList.add('active-write');
        document.getElementById('wb-info').innerText = `x${data.wb.rd} = 0x${data.wb.wdata.toString(16)}`;
    } else {
        document.getElementById('wb-info').innerText = "Idle";
    }

    // C. Highlight Read Registers (Blue)
    if (data.id) {
        const rs1 = Number(data.id.rs1);
        const rs2 = Number(data.id.rs2);
        if (rs1 !== 0) document.getElementById(`reg-box-${rs1}`)?.classList.add('active-read');
        if (rs2 !== 0) document.getElementById(`reg-box-${rs2}`)?.classList.add('active-read');
    }

    updateHazardPanel(data);
    updateSVG(data, regs);
});

function updateHazardPanel(data) {
    const hazList = document.getElementById('hazard-list');
    if (!hazList) return;

    let hazards = [];
    if (data.hazard) {
        if (data.hazard.if_stall) hazards.push("⚠️ IF STALL (Fetch Wait)");
        if (data.hazard.id_stall) hazards.push("⚠️ ID STALL (Load-Use)");
        if (data.hazard.flush)    hazards.push("🚿 FLUSH (Branch Mispredict)");
    }

    if (data.fwd) {
        if (data.fwd.a_sel == 1) hazards.push("⚡ FWD: MEM → EX (Op A)");
        if (data.fwd.a_sel == 2) hazards.push("⚡ FWD: WB → EX (Op A)");
        if (data.fwd.b_sel == 1) hazards.push("⚡ FWD: MEM → EX (Op B)");
        if (data.fwd.b_sel == 2) hazards.push("⚡ FWD: WB → EX (Op B)");
    }

    if (hazards.length === 0) {
        hazList.innerHTML = '<div style="color:#666; padding:5px;">No Active Hazards</div>';
    } else {
        hazList.innerHTML = hazards.map(h => `<div style="padding:5px; border-bottom:1px solid #444; font-weight:bold; color: #ffcc00;">${h}</div>`).join('');
    }
}

function updateSVG(data, regs) {
    const svgObj = document.getElementById('pipeline-svg');
    if (!svgObj.contentDocument) return;
    const svg = svgObj.contentDocument;

    const setText = (id, val) => { const e = svg.getElementById(id); if(e) e.textContent = val; };
    const setFill = (id, col) => { const e = svg.getElementById(id); if(e) e.style.fill = col; };
    const setWire = (id, color, width, dash) => {
        const e = svg.getElementById(id);
        if(e) {
            e.style.stroke = color;
            e.style.strokeWidth = width;
            e.style.strokeDasharray = dash;
        }
    };

    // 1. Text & PC Updates
    if (data.asm) {
        ['if','id','ex','mem','wb'].forEach(s => setText(`txt-asm-${s}`, data.asm[s]));
        ['if','id','ex','mem','wb'].forEach(s => setText(`txt-pc-${s}`, data.pc_hex[s]));
    }

    // 2. ID STAGE: Show Register Indices (from Hardware)
    if (data.id) {
        setText('txt-rs1-id', `rs1: x${Number(data.id.rs1)}`);
        setText('txt-rs2-id', `rs2: x${Number(data.id.rs2)}`);
        setText('txt-rd-id',  `-> rd: x${Number(data.id.rd)}`);
    }

    // 3. EX STAGE: Show Actual Operands (from Hardware Wires)
    // This now uses the val_a/val_b passed from the Testbench -> Server
    if (data.ex) {
        setText('txt-op-a', `A: 0x${Number(data.ex.val_a).toString(16)}`);
        setText('txt-op-b', `B: 0x${Number(data.ex.val_b).toString(16)}`);
        setText('txt-alu-ex', `Res: 0x${Number(data.ex.alu_result).toString(16)}`);
    }

    // 4. MEM STAGE
    if (data.mem.we) {
        setText('txt-mem-status', `Store: 0x${Number(data.mem.wdata).toString(16)}`);
    } else {
        setText('txt-mem-status', `Load Addr: 0x${Number(data.mem.addr).toString(16)}`);
    }

    // 5. WB WIRE ANIMATION
    if (data.wb && data.wb.we && data.wb.rd !== 0) {
        setText('txt-wb-reg', `x${data.wb.rd} = 0x${data.wb.wdata.toString(16)}`);
        setFill('txt-wb-reg', '#4ec9b0');
        setWire('wire-wb-back', '#4ec9b0', '3', 'none');
    } else {
        setText('txt-wb-reg', "--");
        setFill('txt-wb-reg', '#666');
        setWire('wire-wb-back', '#444', '2', '5,5');
    }

    // 6. FORWARDING WIRES
    setWire('fwd-mem-id', '#333', '3', '5,5');
    setWire('fwd-wb-id', '#333', '3', '5,5');
    if (data.fwd) {
        if (data.fwd.a_sel == 1 || data.fwd.b_sel == 1) setWire('fwd-mem-id', '#007acc', '5', 'none');
        if (data.fwd.a_sel == 2 || data.fwd.b_sel == 2) setWire('fwd-wb-id', '#4ec9b0', '5', 'none');
    }

    // 7. HAZARD COLORS
    ['if','id'].forEach(s => setFill(`stage-${s}`, '#252526'));
    if (data.hazard && data.hazard.if_stall) setFill('stage-if', '#770000');
    if (data.hazard && data.hazard.id_stall) setFill('stage-id', '#770000');
}