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
    const setWire = (id, color, style) => {
        const e = svg.getElementById(id);
        if(e) {
            e.style.stroke = color;
            e.style.strokeWidth = (style === 'active') ? "3" : "2";
            e.style.strokeDasharray = (style === 'active') ? "none" : "5,5";
        }
    };

    // Text Updates
    if (data.asm) {
        ['if','id','ex','mem','wb'].forEach(s => setText(`txt-asm-${s}`, data.asm[s]));
        ['if','id','ex','mem','wb'].forEach(s => setText(`txt-pc-${s}`, data.pc_hex[s]));
    }

    // EX Operands (Blue Text)
    if (data.ex_info && regs) {
        const valA = regs[data.ex_info.rs1];
        const valB = regs[data.ex_info.rs2];
        setText('txt-op-a', `A: 0x${valA.toString(16)}`);
        setText('txt-op-b', `B: 0x${valB.toString(16)}`);
        setText('txt-alu-ex', `Res: 0x${Number(data.ex.alu_result).toString(16)}`);
    }

    // MEM Stage (Always Active - No "Jump" Logic)
    const memBox = svg.getElementById('stage-mem');
    if(memBox) memBox.style.opacity = "1.0";

    // Display Memory Activity if 'we' is high, otherwise show Addr
    if (data.mem.we) setText('txt-mem-status', `Wr: 0x${Number(data.mem.wdata).toString(16)}`);
    else setText('txt-mem-status', `Rd Addr: 0x${Number(data.mem.addr).toString(16)}`);

    // Wire Animation (WB -> RegFile)
    if (data.wb && data.wb.we && data.wb.rd !== 0) {
        setText('txt-wb-reg', `x${data.wb.rd} = 0x${data.wb.wdata.toString(16)}`);
        setFill('txt-wb-reg', '#4ec9b0');
        setWire('wire-wb-back', '#4ec9b0', 'active');
    } else {
        setText('txt-wb-reg', "--");
        setFill('txt-wb-reg', '#666');
        setWire('wire-wb-back', '#444', 'idle');
    }

    // Hazards
    ['if','id'].forEach(s => setFill(`stage-${s}`, '#252526'));
    if (data.hazard && data.hazard.if_stall) setFill('stage-if', '#770000');
    if (data.hazard && data.hazard.id_stall) setFill('stage-id', '#770000');
}