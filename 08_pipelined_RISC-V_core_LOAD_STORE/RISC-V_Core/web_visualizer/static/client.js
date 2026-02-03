const socket = io();
const regGrid = document.getElementById('reg-grid');
regGrid.innerHTML = "";
for (let i = 0; i < 32; i++) {
    const div = document.createElement('div');
    div.className = 'reg-box';
    div.id = `reg-box-${i}`;
    div.innerHTML = `<span class="reg-name">x${i}</span><div class="reg-val" id="reg-val-${i}">0</div>`;
    regGrid.appendChild(div);
}

document.addEventListener('keydown', (e) => {
    if (e.key === "ArrowRight") sendCommand('step');
    if (e.key === "ArrowLeft")  sendCommand('back');
});

function sendCommand(action) { socket.emit('command', { action: action }); }

socket.on('update', (packet) => {
    const data = packet.enriched;
    const regs = packet.registers;
    document.getElementById('cycle-display').innerText = data.cycle || 0;

    // 1. Reset Register Grid
    for(let i=0; i<32; i++) {
        const box = document.getElementById(`reg-box-${i}`);
        box.classList.remove('active-write', 'active-read');
        document.getElementById(`reg-val-${i}`).innerText = "0x" + regs[i].toString(16);
    }

    // 2. Highlight WB (Green) - Direct from Hardware Signal
    if (data.wb && data.wb.we && data.wb.rd !== 0) {
        const box = document.getElementById(`reg-box-${data.wb.rd}`);
        if(box) box.classList.add('active-write');
        document.getElementById('wb-info').innerText = `x${data.wb.rd} = 0x${data.wb.wdata.toString(16)}`;
    } else {
        document.getElementById('wb-info').innerText = "--";
    }

    // 3. Highlight EX Operands (Blue)
    if (data.ex_info) {
        const rs1 = data.ex_info.rs1;
        const rs2 = data.ex_info.rs2;
        if (rs1 !== 0) document.getElementById(`reg-box-${rs1}`)?.classList.add('active-read');
        if (rs2 !== 0) document.getElementById(`reg-box-${rs2}`)?.classList.add('active-read');
    }

    updateSVG(data, regs);
});

function updateSVG(data, regs) {
    const svgObj = document.getElementById('pipeline-svg');
    if (!svgObj.contentDocument) return;
    const svg = svgObj.contentDocument;

    const setText = (id, val) => { const e = svg.getElementById(id); if(e) e.textContent = val; };
    const setFill = (id, col) => { const e = svg.getElementById(id); if(e) e.style.fill = col; };

    // ... (Text Updates for ASM/PC remain the same) ...

    // --- ID STAGE (Keep showing Registers) ---
    // Here we DO want to show register indices (rs1, rs2)
    if (data.id) {
        setText('txt-rs1-id', `rs1: x${data.id.rs1}`);
        setText('txt-rs2-id', `rs2: x${data.id.rs2}`);
        setText('txt-rd-id',  `-> rd: x${data.id.rd}`);
    }

    // --- EX STAGE (The Fix!) ---
    // Display the ACTUAL values entering the ALU (from Hardware)
    if (data.ex) {
        // data.ex.val_a/val_b come from server.py which got them from Chisel
        setText('txt-op-a', `A: 0x${data.ex.val_a.toString(16)}`);
        setText('txt-op-b', `B: 0x${data.ex.val_b.toString(16)}`);
        setText('txt-alu-ex', `Res: 0x${Number(data.ex.alu_result).toString(16)}`);
    }

    // ... (Rest of function for MEM/WB/Hazards remains the same) ...
}